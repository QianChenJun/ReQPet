package io.github.reqpet.engine

import io.github.reqpet.hook.HookLog
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * EngineLog 文本镜像：常开落盘，不依赖 logcat 环形缓冲与调试开关，供事后离线回看。
 * 与 runtime.log 同目录，文件名 text.log，轮转为 text.1.log / text.2.log。
 */
internal object EngineTextLog {

    private const val CAPACITY = 4096

    /** 单行字符上限：必须让 UTF-8 字节数留在 RuntimeDiagnosticFormat.MAX_RECORD_BYTES 以内。 */
    private const val MAX_LINE_CHARS = 1_800
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024
    private const val ROTATED_FILES = 2

    private val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val queue = ArrayBlockingQueue<String>(CAPACITY)
    private val bound = AtomicBoolean()
    private val warned = AtomicBoolean()

    /** 只在诊断目录确定后调用一次；目录不可写时静默降级，不影响宿主。 */
    fun bind(directory: File) {
        if (!bound.compareAndSet(false, true)) return
        try {
            Thread({
                try {
                    RuntimeDiagnosticFileWriter(
                        directory,
                        maxFileBytes = MAX_FILE_BYTES,
                        rotatedFiles = ROTATED_FILES,
                        baseName = "text"
                    ).use { writer ->
                        while (true) writer.append(queue.take())
                    }
                } catch (_: Throwable) {
                    warnOnce()
                }
            }, "qpet-text-log").apply { isDaemon = true }.start()
        } catch (_: Throwable) {
            warnOnce()
        }
    }

    fun append(level: EngineLog.Level, source: String, message: String) {
        if (!bound.get()) return
        try {
            val head = "[${LocalDateTime.now().format(formatter)}] ${level.tag} [$source] "
            val flat = message.replace('\r', ' ').replace('\n', ' ')
            val room = MAX_LINE_CHARS - head.length
            val body = if (flat.length > room) flat.substring(0, room.coerceAtLeast(1)) else flat
            queue.offer("$head$body\n")
        } catch (_: Throwable) {
            // 日志落盘绝不改变宿主行为。
        }
    }

    private fun warnOnce() {
        if (!warned.compareAndSet(false, true)) return
        try {
            HookLog.w(EngineLog.TAG, "Text log unavailable: writer stopped")
        } catch (_: Throwable) {
            // Fixed text only.
        }
    }
}
