package io.github.reqpet.engine

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 任务台账：把「什么时候开过什么任务、什么时候结算」压成一行一条的可读时间线。
 *
 * 只在任务起止与结算结果上落笔（轮询、睡眠等高频事件不进台账），用于回答
 * 「今天到底开过几门课/几次探险、有没有重复结算」这类问题，而不必去 runtime.log 里
 * 按哈希 story id 反查。写入复用 RuntimeDiagnosticFileWriter，因此同样有大小轮转。
 */
internal object TaskLedger {

    private const val TAG = "TaskLedger"
    private const val MAX_LINE_CHARS = 800
    private const val QUEUE_CAPACITY = 512
    private const val MAX_FILE_BYTES = 1L * 1024 * 1024
    private const val ROTATED_FILES = 1

    private val recorded = setOf("task_started", "task_settled", "settlement_result")

    private val stampFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    @Volatile
    var directory: File? = null

    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)
    private val writerStarted = AtomicBoolean(false)

    fun record(name: String, fields: Array<out Pair<String, Any?>>) {
        if (name !in recorded) return
        val dir = directory ?: return
        // 写入器要求「恰好一条以换行结尾的记录」，因此字段内换行先压平，行尾统一补换行。
        val line = buildString {
            append(LocalDateTime.now().format(stampFormat)).append(' ').append(name)
            for ((key, value) in fields) {
                append(' ').append(key).append('=').append(value ?: "-")
            }
        }.replace('\n', ' ').replace('\r', ' ').take(MAX_LINE_CHARS) + "\n"
        if (!queue.offer(line)) return
        if (writerStarted.compareAndSet(false, true)) {
            Thread({ writeLoop(dir) }, "qpet-task-ledger").apply { isDaemon = true }.start()
        }
    }

    private fun writeLoop(dir: File) {
        try {
            RuntimeDiagnosticFileWriter(
                dir, maxFileBytes = MAX_FILE_BYTES, rotatedFiles = ROTATED_FILES, baseName = "ledger"
            ).use { writer ->
                while (true) writer.append(queue.take())
            }
        } catch (_: Throwable) {
            // 台账是排查辅助，任何失败都不能影响宿主进程。
            EngineLog.w(TAG, "Task ledger writer stopped")
        }
    }
}
