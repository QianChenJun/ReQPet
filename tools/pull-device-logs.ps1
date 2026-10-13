# 一键采集设备端模块日志并输出摘要。
#   pwsh tools/pull-device-logs.ps1                 # 拉取 + 摘要
#   pwsh tools/pull-device-logs.ps1 -Mode watch -Seconds 90
#                                                  # 记录基线 -> 等待 -> 只看新增行（抓一次性信令）
#   pwsh tools/pull-device-logs.ps1 -Mode digest    # 只分析上一次采集目录
#   pwsh tools/pull-device-logs.ps1 -RestartQQ      # 采集前重启宿主 QQ
# 采集内容：qpet-diagnostics 下全部日志、oidb_trace.log、宿主 prefs、logcat、最近诊断包。

[CmdletBinding()]
param(
    [string]$Serial = "",
    [ValidateSet("pull", "watch", "digest", "auto")][string]$Mode = "pull",
    [int]$Seconds = 90,
    [int]$WaitMinutes = 20,
    [string]$OutDir = "",
    [switch]$RestartQQ
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$hostPkg = "com.tencent.mobileqq"
$diagRemote = "/storage/emulated/0/Android/data/$hostPkg/files/qpet-diagnostics"
$stageRemote = "/sdcard/Download/reqpet-stage"

function Resolve-Adb {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }
    $candidates += "D:\Environment\Android\SDK\platform-tools\adb.exe"
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return $candidate }
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw "找不到 adb：设置 ANDROID_HOME 或把 platform-tools 加入 PATH"
}

function Resolve-Serial($adb, $serial) {
    $online = & $adb devices | Select-Object -Skip 1 |
        Where-Object { $_ -match "\tdevice$" } | ForEach-Object { ($_ -split "\t")[0] }
    if ($serial) {
        if ($online -notcontains $serial) { throw "设备 $serial 不在线（当前：$($online -join ', ')）" }
        return $serial
    }
    if (-not $online) { throw "没有已授权设备：连接 USB 并在手机上允许调试" }
    return $online[0]
}

function Invoke-Adb($adb, $serial, [string[]]$arguments, [int]$TimeoutSec = 180) {
    $job = Start-Job -ScriptBlock {
        param($exe, $target, $argv)
        & $exe -s $target @argv 2>&1
    } -ArgumentList $adb, $serial, $arguments
    try {
        if (-not (Wait-Job $job -Timeout $TimeoutSec)) {
            Stop-Job $job
            throw "adb 调用超时（${TimeoutSec}s）：$($arguments -join ' ')"
        }
        return @(Receive-Job $job)
    } finally {
        Remove-Job $job -Force -ErrorAction SilentlyContinue
    }
}

function Get-Root($adb, $serial) {
    $out = (Invoke-Adb $adb $serial @("shell", "su -c id")) -join "`n"
    return ($out -match "uid=0")
}

function Get-Text($path) {
    if (-not (Test-Path $path)) { return @() }
    return @(Get-Content -LiteralPath $path -Encoding utf8)
}

function Show-Tail($title, $lines, $count) {
    Write-Host ""
    Write-Host "== $title ==" -ForegroundColor Cyan
    if (-not $lines -or $lines.Count -eq 0) { Write-Host "  (无)"; return }
    $lines | Select-Object -Last $count | ForEach-Object { "  $_" }
}

function Show-Digest($outDir) {
    $textLines = Get-Text (Join-Path $outDir "text.log")
    $runtimeLines = Get-Text (Join-Path $outDir "runtime.log")
    $traceLines = Get-Text (Join-Path $outDir "oidb_trace.log")
    $prefsPath = Join-Path $outDir "qqpet_inproc_prefs.xml"

    Write-Host ""
    Write-Host "===== 摘要 =====" -ForegroundColor Yellow
    Write-Host "  采集目录: $outDir"
    Write-Host "  文本日志: $($textLines.Count) 行 | 结构化事件: $($runtimeLines.Count) 行 | 抓包: $($traceLines.Count) 行"

    if (Test-Path $prefsPath) {
        $keys = "key_master_enabled", "key_study", "key_work", "key_adventure", "key_care", "key_settle",
        "key_fatigue_to_adventure", "key_auto_use_study_item", "key_debug_log", "key_action_toast",
        "key_study_mode", "key_work_mode"
        Write-Host "  开关:"
        foreach ($key in $keys) {
            $match = Select-String -LiteralPath $prefsPath -Pattern "name=`"$key`"" | Select-Object -First 1
            if ($match) { Write-Host ("    {0}" -f $match.Line.Trim()) }
        }
    }

    Show-Tail "[配置] 最近回显" ($textLines | Select-String -Pattern "\[配置\]" | ForEach-Object { $_.Line }) 2
    Show-Tail "学习道具" ($textLines | Select-String -Pattern "\[学习道具\]|\[道具库存\]" | ForEach-Object { $_.Line }) 12
    Show-Tail "结算/校核" ($textLines | Select-String -Pattern "\[结算\]|结算|状态校核|静默放行" | Where-Object { $_.Line -notmatch "\[配置\]" } | ForEach-Object { $_.Line }) 10
    Show-Tail "告警与错误" ($textLines | Select-String -Pattern "\bW \[|失败|未成功|错误" | ForEach-Object { $_.Line }) 12

    if ($traceLines.Count -gt 0) {
        $ownLines = $traceLines | Select-String -Pattern "\[oidb-own\]"
        $hostLines = $traceLines | Select-String -Pattern "\[oidb\]"
        Write-Host ""
        Write-Host "== 抓包 ==" -ForegroundColor Cyan
        Write-Host "  模块自发: $($ownLines.Count) 条 | 宿主自发: $($hostLines.Count) 条"
        Show-Tail "模块发包（最后 10）" ($ownLines | ForEach-Object { $_.Line }) 10
        Show-Tail "0x994b 使用道具（全部）" ($traceLines | Select-String -Pattern "0x994b" | ForEach-Object { $_.Line }) 20
    }

    if ($runtimeLines.Count -gt 0) {
        Show-Tail "结构化事件（最后 15）" $runtimeLines 15
    }
    Write-Host ""
}

function Invoke-Watch($adb, $serial, $outDir, $seconds) {
    Write-Host "基线：清空 logcat，等待 $seconds 秒，请现在做要观测的操作（例如领 SVIP 礼包）..."
    Invoke-Adb $adb $serial @("logcat", "-c") | Out-Null
    $baseline = (Invoke-Adb $adb $serial @("shell", "wc -l < /data/data/$hostPkg/files/oidb_trace.log")).Trim()
    Start-Sleep -Seconds $seconds
    Write-Host "采集新增内容（抓包基线行数 $baseline）"
    return $baseline
}

$adb = Resolve-Adb

if (-not $OutDir -and $Mode -ne "digest") {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $OutDir = Join-Path $repoRoot "tmp\captures\$stamp"
}
if (-not $OutDir) {
    $latest = Get-ChildItem -Directory (Join-Path $repoRoot "tmp\captures") -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $latest) { throw "没有可分析的采集目录，请先执行 pull" }
    $OutDir = $latest.FullName
}
if ($Mode -eq "digest") { Show-Digest $OutDir; return }

if ($Mode -eq "auto") {
    Write-Host "等待设备接入（最多 $WaitMinutes 分钟）..."
    $deadline = (Get-Date).AddMinutes($WaitMinutes)
    while ((Get-Date) -lt $deadline) {
        $found = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" })
        if ($found.Count -gt 0) { break }
        Start-Sleep -Seconds 3
    }
    if ($found.Count -eq 0) { throw "等待 $WaitMinutes 分钟仍无设备" }
    Write-Host "设备已接入，10 秒后开始采集..."
    Start-Sleep -Seconds 10
}

$serial = Resolve-Serial $adb $Serial
$root = Get-Root $adb $serial

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$baseline = $null
if ($Mode -eq "watch") { $baseline = Invoke-Watch $adb $serial $OutDir $Seconds }

if ($RestartQQ) {
    Write-Host "重启宿主 QQ..."
    Invoke-Adb $adb $serial @("shell", "am", "force-stop", $hostPkg) | Out-Null
    Invoke-Adb $adb $serial @("shell", "monkey", "-p", $hostPkg, "-c", "android.intent.category.LAUNCHER", "1") | Out-Null
    Start-Sleep -Seconds 20
}

Write-Host "设备: $serial (root=$root) -> $OutDir"
Invoke-Adb $adb $serial @("shell", "rm -rf $stageRemote; mkdir -p $stageRemote") | Out-Null

$collect = "mkdir -p $stageRemote; " +
"cp -f $diagRemote/*.log $stageRemote/ 2>/dev/null; " +
"cp -f /data/data/$hostPkg/files/oidb_trace.log $stageRemote/ 2>/dev/null; " +
"cp -f /data/data/$hostPkg/shared_prefs/qqpet_inproc_prefs.xml $stageRemote/ 2>/dev/null; " +
"ls -t /sdcard/Download/ReQPet/*.zip /sdcard/Download/ReQPet/auto/*.zip 2>/dev/null | head -1 > $stageRemote/newest-export.txt; " +
"zip=`$(head -1 $stageRemote/newest-export.txt); [ -n `"`$zip`" ] && cp -f `"`$zip`" $stageRemote/; " +
"chmod -R 666 $stageRemote 2>/dev/null; echo done"

if ($root) { $collect = "su -c `"$collect`"" }
$output = (Invoke-Adb $adb $serial @("shell", $collect)) -join "`n"
if ($output -notmatch "done") { Write-Host "采集脚本输出异常：$output" -ForegroundColor DarkYellow }

Invoke-Adb $adb $serial @("pull", "$stageRemote", "$OutDir") | Out-Null
Invoke-Adb $adb $serial @("shell", "rm -rf $stageRemote") | Out-Null
Invoke-Adb $adb $serial @("logcat", "-d", "-v", "time", "-t", "6000", "-s", "QQPetCopilot", "QPetTrace") |
    Set-Content -LiteralPath (Join-Path $OutDir "logcat.txt") -Encoding utf8

# 采集目录结构整理：stage 目录内容上移一层，便于直接读文件名。
$nested = Join-Path $OutDir (Split-Path -Leaf $stageRemote)
if (Test-Path $nested) {
    Get-ChildItem -LiteralPath $nested | ForEach-Object { Move-Item -Force $_.FullName $OutDir }
    Remove-Item -Recurse -Force $nested
}

if ($baseline) {
    $trace = Get-Text (Join-Path $OutDir "oidb_trace.log")
    $newLines = if ($baseline -match '^\d+$') { $trace | Select-Object -Skip ([int]$baseline) } else { $trace }
    Write-Host ""
    Write-Host "== 新增抓包（$($newLines.Count) 行）" -ForegroundColor Cyan
    $newLines | ForEach-Object { "  $_" }
}

Show-Digest $OutDir
