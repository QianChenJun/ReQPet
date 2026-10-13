# 一键采集设备端模块日志并输出摘要。
#   pwsh tools/pull-device-logs.ps1                 # 拉取 + 摘要
#   pwsh tools/pull-device-logs.ps1 -Mode watch -Seconds 90
#                                                  # 记基线 -> 等待 -> 只看新增行（抓一次性信令）
#   pwsh tools/pull-device-logs.ps1 -Mode auto -WaitMinutes 30
#                                                  # 等设备接入后自动采集
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
$exportRemote = "/sdcard/Download/ReQPet"
$stageRemote = "/sdcard/Download/reqpet-stage"

function Quote-Arg([string]$value) {
    if ($value -match '[\s"]') {
        return '"' + ($value -replace '(\\)"', '$1\"') + '"'
    }
    return $value
}

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

function Invoke-Adb {
    param(
        [string]$Adb,
        [string]$Serial,
        [string[]]$Arguments,
        [int]$TimeoutSec = 180,
        [switch]$AllowTimeout
    )
    $argList = @()
    if ($Serial) { $argList += @("-s", $Serial) }
    $argList += $Arguments
    $quoted = ($argList | ForEach-Object { Quote-Arg $_ }) -join ' '
    $outFile = [System.IO.Path]::GetTempFileName()
    $errFile = [System.IO.Path]::GetTempFileName()
    try {
        $proc = Start-Process -FilePath $Adb -ArgumentList $quoted -NoNewWindow -PassThru `
            -RedirectStandardOutput $outFile -RedirectStandardError $errFile
        if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
            try { $proc.Kill($true) } catch { }
            if ($AllowTimeout) { return @() }
            throw "adb 调用超时（${TimeoutSec}s）：$($Arguments -join ' ')"
        }
        $lines = @()
        $lines += Get-Content -LiteralPath $outFile -Encoding utf8 -ErrorAction SilentlyContinue
        $lines += Get-Content -LiteralPath $errFile -Encoding utf8 -ErrorAction SilentlyContinue
        return @($lines)
    } finally {
        Remove-Item $outFile, $errFile -Force -ErrorAction SilentlyContinue
    }
}

function Get-OnlineSerials($adb) {
    $lines = Invoke-Adb -Adb $adb -Arguments @("devices") -TimeoutSec 30
    $serials = @()
    foreach ($line in $lines) {
        if ($line -match "^(\S+)\s+device") { $serials += $Matches[1] }
    }
    return @($serials)
}

function Get-Text($path) {
    if (-not (Test-Path $path)) { return @() }
    return @(Get-Content -LiteralPath $path -Encoding utf8)
}

function Show-Tail($title, $lines, $count) {
    Write-Host ""
    Write-Host "== $title ==" -ForegroundColor Cyan
    $lines = @($lines)
    if ($lines.Count -eq 0) { Write-Host "  (无)"; return }
    $lines | Select-Object -Last $count | ForEach-Object { "  $_" }
}

function Show-Digest($outDir) {
    $textLines = Get-Text (Join-Path $outDir "text.log")
    $traceLines = Get-Text (Join-Path $outDir "oidb_trace.log")
    $runtimeLines = Get-Text (Join-Path $outDir "runtime.log")
    $prefsPath = Join-Path $outDir "qqpet_inproc_prefs.xml"

    Write-Host ""
    Write-Host "===== 摘要 =====" -ForegroundColor Yellow
    Write-Host "  采集目录: $outDir"
    Write-Host "  文本日志: $($textLines.Count) 行 | 结构化事件: $($runtimeLines.Count) 行 | 抓包: $($traceLines.Count) 行"

    if (Test-Path $prefsPath) {
        Write-Host "  开关:"
        foreach ($key in @(
                "key_master_enabled", "key_study", "key_work", "key_adventure", "key_care", "key_settle",
                "key_fatigue_to_adventure", "key_auto_use_study_item", "key_debug_log", "key_action_toast",
                "key_study_mode", "key_work_mode")) {
            $hit = Select-String -LiteralPath $prefsPath -Pattern "name=`"$key`"" | Select-Object -First 1
            if ($hit) { Write-Host ("    " + $hit.Line.Trim()) }
        }
    }

    Show-Tail "[配置] 最近回显" ($textLines | Select-String -Pattern "\[配置\]" | ForEach-Object { $_.Line }) 2
    Show-Tail "学习道具与库存采样" ($textLines | Select-String -Pattern "\[学习道具\]|\[道具库存\]" | ForEach-Object { $_.Line }) 14
    Show-Tail "结算/状态校核" ($textLines |
        Select-String -Pattern "\[结算\]|结算|状态校核|静默放行|未确认结算" |
        Where-Object { $_.Line -notmatch "\[配置\]" } |
        ForEach-Object { $_.Line }) 10
    Show-Tail "任务起停" ($textLines |
        Select-String -Pattern "\[任务进行中\]|\[学习成功\]|\[探险成功\]|\[打工调度\]|任务调度" |
        ForEach-Object { $_.Line }) 12
    Show-Tail "告警与错误" ($textLines | Select-String -Pattern " W \[|失败|未成功|错误" | ForEach-Object { $_.Line }) 12

    if ($traceLines.Count -gt 0) {
        $ownLines = @($traceLines | Select-String -Pattern "\[oidb-own\]" | ForEach-Object { $_.Line })
        $hostLines = @($traceLines | Select-String -Pattern "\[oidb\]" | ForEach-Object { $_.Line })
        Write-Host ""
        Write-Host "== 抓包 ==" -ForegroundColor Cyan
        Write-Host "  模块自发: $($ownLines.Count) 条 | 宿主自发: $($hostLines.Count) 条"
        Show-Tail "模块发包（最后 10）" $ownLines 10
        Show-Tail "0x994b 使用道具（最后 20）" ($traceLines | Select-String -Pattern "0x994b" | ForEach-Object { $_.Line }) 20
    }

    if ($runtimeLines.Count -gt 0) {
        Show-Tail "结构化事件（最后 12）" $runtimeLines 12
    }
    Write-Host ""
}

$adb = Resolve-Adb

# 采集目录解析：digest 用最近一次，其它模式新建。
if (-not $OutDir -and $Mode -ne "digest") {
    $OutDir = Join-Path $repoRoot ("tmp\captures\" + (Get-Date -Format "yyyyMMdd-HHmmss"))
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
    while ((Get-Date) -lt $deadline -and (Get-OnlineSerials $adb).Count -eq 0) {
        Start-Sleep -Seconds 3
    }
    if ((Get-OnlineSerials $adb).Count -eq 0) { throw "等待 $WaitMinutes 分钟仍无设备" }
    Write-Host "设备已接入，10 秒后开始采集..."
    Start-Sleep -Seconds 10
}

$online = Get-OnlineSerials $adb
if ($Serial) {
    if ($online -notcontains $Serial) { throw "设备 $Serial 不在线（当前：$($online -join ', ')）" }
    $target = $Serial
} else {
    if ($online.Count -eq 0) { throw "没有已授权设备：连接 USB 并在手机上允许调试" }
    $target = $online[0]
}

$rootOutput = (Invoke-Adb -Adb $adb -Serial $target -Arguments @("shell", "su -c id") -TimeoutSec 30 -AllowTimeout) -join "`n"
$hasRoot = $rootOutput -match "uid=0"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Write-Host "设备: $target (root=$hasRoot) -> $OutDir"

if ($Mode -eq "watch") {
    Write-Host "记录基线并清空 logcat，等待 $Seconds 秒：请现在做要观测的操作（例如领 SVIP 礼包）..."
    Invoke-Adb -Adb $adb -Serial $target -Arguments @("logcat", "-c") -TimeoutSec 30 -AllowTimeout | Out-Null
    $watchBaseline = 0
    $countLine = (Invoke-Adb -Adb $adb -Serial $target -Arguments @("shell", "wc -l < /data/data/$hostPkg/files/oidb_trace.log") -TimeoutSec 30 -AllowTimeout) -join ""
    if ($countLine -match "(\d+)") { $watchBaseline = [int]$Matches[1] }
    Start-Sleep -Seconds $Seconds
    Write-Host "等待结束（抓包基线行数 $watchBaseline）"
}

if ($RestartQQ) {
    Write-Host "重启宿主 QQ..."
    Invoke-Adb -Adb $adb -Serial $target -Arguments @("shell", "am force-stop $hostPkg") -TimeoutSec 60 -AllowTimeout | Out-Null
    Invoke-Adb -Adb $adb -Serial $target -Arguments @("shell", "monkey -p $hostPkg -c android.intent.category.LAUNCHER 1") -TimeoutSec 60 -AllowTimeout | Out-Null
    Start-Sleep -Seconds 20
}

$newestZip = ((Invoke-Adb -Adb $adb -Serial $target `
        -Arguments @("shell", "ls -t $exportRemote/*.zip $exportRemote/auto/*.zip 2>/dev/null | head -1") `
        -TimeoutSec 60 -AllowTimeout) -join "").Trim()

$zipCopy = ""
if ($newestZip) { $zipCopy = "cp -f $newestZip $stageRemote/ 2>/dev/null; " }

$collect = "mkdir -p $stageRemote; " +
"cp -f $diagRemote/*.log $stageRemote/ 2>/dev/null; " +
"cp -f /data/data/$hostPkg/files/oidb_trace.log $stageRemote/ 2>/dev/null; " +
"cp -f /data/data/$hostPkg/shared_prefs/qqpet_inproc_prefs.xml $stageRemote/ 2>/dev/null; " +
$zipCopy +
"chmod -R 666 $stageRemote 2>/dev/null; echo done"

$shellCommand = if ($hasRoot) { "su -c '$collect'" } else { $collect }
$collectOutput = (Invoke-Adb -Adb $adb -Serial $target -Arguments @("shell", $shellCommand) -TimeoutSec 120) -join "`n"
if ($collectOutput -notmatch "done") {
    Write-Host "采集脚本输出异常：$collectOutput" -ForegroundColor DarkYellow
}

Invoke-Adb -Adb $adb -Serial $target -Arguments @("pull", $stageRemote, $OutDir) -TimeoutSec 300 | Out-Null
Invoke-Adb -Adb $adb -Serial $target -Arguments @("shell", "rm -rf $stageRemote") -TimeoutSec 60 -AllowTimeout | Out-Null

$logcatLines = Invoke-Adb -Adb $adb -Serial $target `
    -Arguments @("logcat", "-d", "-v", "time", "-t", "6000", "-s", "QQPetCopilot", "QPetTrace") `
    -TimeoutSec 60 -AllowTimeout
if (@($logcatLines).Count -gt 0) {
    Set-Content -LiteralPath (Join-Path $OutDir "logcat.txt") -Value $logcatLines -Encoding utf8
} else {
    Write-Host "logcat 采集为空或超时（不影响其它日志）" -ForegroundColor DarkYellow
}

# stage 目录内容上移一层，便于直接按文件名读取。
$nested = Join-Path $OutDir (Split-Path -Leaf $stageRemote)
if (Test-Path $nested) {
    Get-ChildItem -LiteralPath $nested | ForEach-Object { Move-Item -Force $_.FullName $OutDir }
    Remove-Item -Recurse -Force $nested
}

if ($Mode -eq "watch") {
    $trace = Get-Text (Join-Path $OutDir "oidb_trace.log")
    $newLines = if ($trace.Count -gt $watchBaseline) { $trace | Select-Object -Skip $watchBaseline } else { @() }
    Write-Host ""
    Write-Host "== 新增抓包（$(@($newLines).Count) 行）" -ForegroundColor Green
    @($newLines) | ForEach-Object { "  $_" }
}

Show-Digest $OutDir
