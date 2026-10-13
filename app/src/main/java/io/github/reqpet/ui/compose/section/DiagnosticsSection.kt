package io.github.reqpet.ui.compose.section

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.reqpet.engine.DiagnosticsExporter
import io.github.reqpet.ui.PreferencesHelper
import io.github.reqpet.ui.compose.ActionRow
import io.github.reqpet.ui.compose.SectionHeader
import io.github.reqpet.ui.compose.SettingsGroup
import io.github.reqpet.ui.compose.SettingsState
import io.github.reqpet.ui.compose.ToggleRow
import java.io.File

@Composable
fun DiagnosticsSection(state: SettingsState) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext ?: context }

    var exporting by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    val exportHint = remember {
        "保存到 ${DiagnosticsExporter.RELATIVE_ROOT}（文件管理器可见），" +
            "每日 0 点后自动快照最近 ${DiagnosticsExporter.AUTO_KEEP_COUNT} 份"
    }

    val sharePackage: (android.net.Uri) -> Unit = { uri ->
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(send, "分享诊断包").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
            Toast.makeText(context, "无法直接分享，请在文件管理器打开 ${DiagnosticsExporter.RELATIVE_ROOT}", Toast.LENGTH_LONG).show()
        }
    }

    val externalDir = remember(appContext) {
        try {
            appContext.getExternalFilesDir("qpet-diagnostics")
        } catch (_: Throwable) {
            null
        }
    }
    val fallbackDir = remember(appContext) {
        File(appContext.filesDir, "qpet-diagnostics")
    }

    val externalPath = externalDir?.absolutePath
    val fallbackPath = fallbackDir.absolutePath

    val copyPath: (String, String) -> Unit = { path, label ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, path))
            Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "剪贴板不可用，未复制目录", Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        LogSection(state)

        Spacer(modifier = Modifier.height(16.dp))

        SectionHeader("诊断导出")
        SettingsGroup {
            item {
                ActionRow(
                    title = "导出诊断包",
                    subtitle = "$exportHint\n（打包 runtime/text 事件、OIDB 抓包、开关快照与设备信息）",
                    trailing = if (exporting) "导出中" else "导出",
                    onClick = {
                        if (!exporting) {
                            exporting = true
                            DiagnosticsExporter.exportNow(context) { message ->
                                exporting = false
                                exportResult = message
                                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }
            item {
                val uri = DiagnosticsExporter.lastExportUri()
                ActionRow(
                    title = "分享最近诊断包",
                    subtitle = exportResult ?: "导出后可一键发给排查者",
                    trailing = if (uri != null) "分享" else null,
                    onClick = {
                        if (uri == null) {
                            Toast.makeText(context, "请先导出诊断包", Toast.LENGTH_SHORT).show()
                        } else {
                            sharePackage(uri)
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        SectionHeader("长期日志目录")
        SettingsGroup {
            item {
                ActionRow(
                    title = "首选外置目录",
                    subtitle = if (externalPath != null) {
                        "$externalPath\n（文件系统长期存储，非内存日志；如写入异常将自动降级）"
                    } else {
                        "外置存储不可用，将自动使用内部兜底"
                    },
                    trailing = if (externalPath != null) "复制" else null,
                    onClick = {
                        if (externalPath != null) {
                            copyPath(externalPath, "首选外置日志目录")
                        }
                    }
                )
            }
            item {
                ActionRow(
                    title = "内部兜底目录",
                    subtitle = "$fallbackPath\n（外置目录不可用或无法写入时使用，读取通常需要 root 权限）",
                    trailing = "复制",
                    onClick = {
                        copyPath(fallbackPath, "内部兜底日志目录")
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        MetricsSection(state.engineMetrics)


        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader("操作提示")
        SettingsGroup {
            item {
                ToggleRow(
                    title = "操作结果弹窗",
                    checked = state.bool(PreferencesHelper.KEY_ACTION_TOAST, true),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_ACTION_TOAST, it) },
                    subtitle = "手动操作与后台自动调度时弹出结果提示；关闭后仅记录到模块日志"
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SectionHeader("调试选项")
        SettingsGroup {
            item {
                ToggleRow(
                    title = "调试详细日志",
                    checked = state.bool(PreferencesHelper.KEY_DEBUG_LOG, false),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_DEBUG_LOG, it) },
                    subtitle = "默认静默，开启后向 libxposed 与系统 logcat 打印详细业务日志"
                )
            }
            item {
                ToggleRow(
                    title = "持久化诊断详情",
                    checked = state.bool(PreferencesHelper.KEY_DIAGNOSTICS_DETAIL, false),
                    onCheckedChange = { state.setBool(PreferencesHelper.KEY_DIAGNOSTICS_DETAIL, it) },
                    subtitle = "记录异常类和有限调用帧，不记录错误正文或原始响应；仅在排查时开启"
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun DiagnosticsSectionPreview() {
    MaterialTheme {
        DiagnosticsSection(state = SettingsState(LocalContext.current, null, previewMode = true))
    }
}
