package io.github.reqpet.engine

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import io.github.reqpet.ui.PreferencesHelper
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 诊断包导出：把落盘的 runtime/text 事件与 OIDB 抓包打成 zip，写到用户可见的 Download/ReQPet。
 * 手机无需连接电脑即可取证：手动导出供即时排查，每日自动快照保证「昨天那段」不丢。
 */
object DiagnosticsExporter {

    const val RELATIVE_ROOT = "Download/ReQPet"

    /** 自动快照保留份数，UI 提示与清理策略共用。 */
    const val AUTO_KEEP_COUNT = 3
    private const val AUTO_FOLDER = "auto"
    private const val KEEP_AUTO_PACKAGES = AUTO_KEEP_COUNT
    private const val MAX_SOURCE_BYTES = 4L * 1024 * 1024
    private const val TAG = "DiagnosticsExporter"
    private val stampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val running = AtomicBoolean()

    @Volatile
    private var lastPath: String? = null

    @Volatile
    private var lastUri: android.net.Uri? = null

    fun lastExportPath(): String? = lastPath

    /** MediaStore 落点的可分享 URI；回退到应用目录导出时为空。 */
    fun lastExportUri(): android.net.Uri? = lastUri

    /** 每日一次自动快照；同日重复调用立即返回，可在主循环里廉价地反复调用。 */
    fun autoSnapshotDaily(context: Context) {
        val app = context.applicationContext ?: context
        val prefs = try {
            app.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            return
        }
        val today = LocalDate.now().toString()
        if (prefs.getString(PreferencesHelper.KEY_DIAG_EXPORT_DATE, "") == today) return
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                val path = performExport(app, AUTO_FOLDER)
                prefs.edit().putString(PreferencesHelper.KEY_DIAG_EXPORT_DATE, today).apply()
                EngineLog.i(TAG, "[诊断导出] 每日快照完成: $path")
            } catch (t: Throwable) {
                EngineLog.w(TAG, "[诊断导出] 每日快照失败: ${t.javaClass.simpleName}")
            } finally {
                running.set(false)
            }
        }, "qpet-diag-auto").apply { isDaemon = true }.start()
    }

    /** 手动导出；结果文本回调到主线程，可直接用于 Toast。 */
    fun exportNow(context: Context, onFinished: (String) -> Unit) {
        val app = context.applicationContext ?: context
        if (!running.compareAndSet(false, true)) {
            onFinished("上一次导出仍在进行，请稍后再试")
            return
        }
        Thread({
            val message = try {
                "已导出到 ${performExport(app, null)}"
            } catch (t: Throwable) {
                EngineLog.w(TAG, "[诊断导出] 手动导出失败: ${t.javaClass.simpleName}")
                "导出失败: ${t.javaClass.simpleName}"
            } finally {
                running.set(false)
            }
            Handler(Looper.getMainLooper()).post { onFinished(message) }
        }, "qpet-diag-export").apply { isDaemon = true }.start()
    }

    /** 返回可直接展示的落点（Download 相对路径或绝对路径）。 */
    private fun performExport(context: Context, folder: String?): String {
        val name = "diag-${LocalDateTime.now().format(stampFormatter)}.zip"
        val staging = File(context.cacheDir, name)
        lastUri = null
        try {
            FileOutputStream(staging).use { buildZip(context, it, folder) }
            val path = publish(context, folder, name, staging)
            lastPath = path
            pruneAutoPackages(context)
            return path
        } finally {
            staging.delete()
        }
    }

    private fun buildZip(context: Context, output: OutputStream, folder: String?) {
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            diagnosticsFiles(context).forEach { file ->
                addEntry(zip, file.name, readCapped(file))
            }
            File(context.filesDir, "oidb_trace.log").takeIf { it.isFile }?.let { trace ->
                addEntry(zip, "oidb_trace.log", readCapped(trace))
            }
            addEntry(zip, "meta.txt", metaText(context, folder).toByteArray(Charsets.UTF_8))
        }
    }

    /** 外置目录优先；两个目录都取，同名以外置为准。 */
    private fun diagnosticsFiles(context: Context): List<File> {
        val directories = listOfNotNull(
            try {
                context.getExternalFilesDir("qpet-diagnostics")
            } catch (_: Throwable) {
                null
            },
            File(context.filesDir, "qpet-diagnostics")
        )
        val collected = LinkedHashMap<String, File>()
        directories.forEach { directory ->
            directory.listFiles()?.sortedBy { it.name }?.forEach { file ->
                if (file.isFile && file.name.endsWith(".log")) collected.putIfAbsent(file.name, file)
            }
        }
        return collected.values.toList()
    }

    private fun readCapped(file: File): ByteArray = try {
        if (file.length() <= MAX_SOURCE_BYTES) {
            file.readBytes()
        } else {
            java.io.RandomAccessFile(file, "r").use { input ->
                input.seek(file.length() - MAX_SOURCE_BYTES)
                ByteArray(MAX_SOURCE_BYTES.toInt()).also { input.readFully(it) }
            }
        }
    } catch (_: Throwable) {
        "<<unreadable>>".toByteArray(Charsets.UTF_8)
    }

    private fun addEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun publish(context: Context, folder: String?, name: String, source: File): String {
        val relative = if (folder.isNullOrEmpty()) RELATIVE_ROOT else "$RELATIVE_ROOT/$folder"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return publishToDownloads(context, relative, name, source)
            } catch (t: Throwable) {
                EngineLog.w(TAG, "[诊断导出] 写入 Download 失败，改用应用外置目录: ${t.javaClass.simpleName}")
            }
        } else {
            try {
                return publishToLegacyDownloads(relative, name, source)
            } catch (t: Throwable) {
                EngineLog.w(TAG, "[诊断导出] 写入 Download 失败，改用应用外置目录: ${t.javaClass.simpleName}")
            }
        }
        return publishToAppExternal(context, folder, name, source)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun publishToDownloads(context: Context, relative: String, name: String, source: File): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("media store insert rejected")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("media store stream unavailable")
            output.use { target -> source.inputStream().use { it.copyTo(target) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            lastUri = uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return "$relative/$name"
    }

    private fun publishToLegacyDownloads(relative: String, name: String, source: File): String {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val directory = File(downloads, relative.removePrefix("Download/"))
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("download directory unavailable")
        val target = File(directory, name)
        source.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        return target.absolutePath
    }

    private fun publishToAppExternal(context: Context, folder: String?, name: String, source: File): String {
        val root = try {
            context.getExternalFilesDir("qpet-diagnostics")
        } catch (_: Throwable) {
            null
        } ?: File(context.filesDir, "qpet-diagnostics")
        val directory = File(root, if (folder.isNullOrEmpty()) "export" else "export/$folder")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("export directory unavailable")
        val target = File(directory, name)
        source.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
        return target.absolutePath
    }

    /** 自动快照只保留最近若干份，避免无人清理时无限增长。 */
    private fun pruneAutoPackages(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val stale = queryAutoPackageIds(context)
            stale.forEach { id ->
                runCatching {
                    context.contentResolver.delete(
                        ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                        null,
                        null
                    )
                }
            }
            return
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        staleFiles(File(downloads, "$RELATIVE_ROOT/$AUTO_FOLDER".removePrefix("Download/"))).forEach { it.delete() }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun queryAutoPackageIds(context: Context): List<Long> {
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val entries = LinkedHashMap<Long, String>()
        listOf("$RELATIVE_ROOT/$AUTO_FOLDER/", "$RELATIVE_ROOT/$AUTO_FOLDER").forEach { candidate ->
            runCatching {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Downloads.RELATIVE_PATH}=?",
                    arrayOf(candidate),
                    null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        entries.putIfAbsent(id, cursor.getString(1) ?: "")
                    }
                }
            }
        }
        if (entries.size <= KEEP_AUTO_PACKAGES) return emptyList()
        return entries.entries
            .sortedByDescending { it.value }
            .drop(KEEP_AUTO_PACKAGES)
            .map { it.key }
    }

    private fun staleFiles(directory: File): List<File> {
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".zip") }.orEmpty()
        if (files.size <= KEEP_AUTO_PACKAGES) return emptyList()
        return files.sortedByDescending { it.name }.drop(KEEP_AUTO_PACKAGES)
    }

    private fun metaText(context: Context, folder: String?): String {
        val builder = StringBuilder()
        builder.append("exported_at=").append(LocalDateTime.now()).append('\n')
        builder.append("reason=").append(if (folder == AUTO_FOLDER) "auto" else "manual").append('\n')
        builder.append("android_sdk=").append(Build.VERSION.SDK_INT).append('\n')
        builder.append("device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        builder.append("host=").append(packageLabel(context, context.packageName)).append('\n')
        builder.append("module=").append(packageLabel(context, "io.github.reqpet")).append('\n')
        builder.append("app_data=").append(context.filesDir.absolutePath).append('\n')
        builder.append("switches:\n")
        val prefs = try {
            context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
        } catch (_: Throwable) {
            null
        }
        SWITCH_KEYS.forEach { key ->
            val value = try {
                prefs?.all?.get(key)?.toString()
            } catch (_: Throwable) {
                null
            } ?: "<unset>"
            builder.append("  ").append(key).append('=').append(value).append('\n')
        }
        return builder.toString()
    }

    private fun packageLabel(context: Context, packageName: String): String = try {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        "$packageName ${info.versionName} (${info.versionCode})"
    } catch (_: Throwable) {
        "$packageName <unknown>"
    }

    /** 开关快照白名单：只含无账号信息的配置项。 */
    private val SWITCH_KEYS = listOf(
        PreferencesHelper.KEY_MASTER_ENABLED,
        PreferencesHelper.KEY_STUDY,
        PreferencesHelper.KEY_WORK,
        PreferencesHelper.KEY_CARE,
        PreferencesHelper.KEY_ADVENTURE,
        PreferencesHelper.KEY_SETTLE,
        PreferencesHelper.KEY_FATIGUE_TO_ADVENTURE,
        PreferencesHelper.KEY_STUDY_MODE,
        PreferencesHelper.KEY_WORK_MODE,
        PreferencesHelper.KEY_SCHOOL_STAGE,
        PreferencesHelper.KEY_COURSE_SUBJECT,
        PreferencesHelper.KEY_COURSE_DURATION,
        PreferencesHelper.KEY_AUTO_USE_STUDY_ITEM,
        PreferencesHelper.KEY_WORK_TYPE,
        PreferencesHelper.KEY_WORK_DURATION,
        PreferencesHelper.KEY_HUMAN_LIKE_SLEEP,
        PreferencesHelper.KEY_NIGHT_SLEEP_MODE,
        PreferencesHelper.KEY_SCREEN_OFF_SILENT,
        PreferencesHelper.KEY_ACTION_TOAST,
        PreferencesHelper.KEY_DEBUG_LOG,
        PreferencesHelper.KEY_DIAGNOSTICS_DETAIL,
        PreferencesHelper.KEY_DIAG_EXPORT_DATE
    )
}
