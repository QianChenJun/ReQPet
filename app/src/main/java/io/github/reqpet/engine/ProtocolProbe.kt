package io.github.reqpet.engine

import io.github.reqpet.hook.OidbSniffer
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 关键协议回包原文落盘。
 *
 * 只记 `code` 与长度无法回答「服务端为什么拒绝」（例如探险 135051、结算失败），
 * 而回包正文与 Bundle 里的错误消息才是判据，因此把原文一并落盘供离线解码。
 * 受调试开关控制，每命令每进程限量，文件名 `probe-<日期>.log`。
 */
internal object ProtocolProbe {

    private const val TAG = "ProtocolProbe"
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_RECORDS_PER_COMMAND = 12
    private const val MAX_HEX_BYTES = 16 * 1024
    private const val HEX_CHARS_PER_LINE = 120
    private const val QUEUE_CAPACITY = 512
    private const val HEX = "0123456789abcdef"

    /** 只盯与调度决策直接相关的命令，避免高频只读轮询灌满磁盘。 */
    private val watched = listOf("0x975e", "0x975f", "0x9760", "0x9ab2", "0x9949", "0x994b", "0x975a")

    private val stampFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    @Volatile
    var directory: File? = null

    private val counts = ConcurrentHashMap<String, Int>()
    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)
    private val writerStarted = AtomicBoolean(false)
    private val fileFullWarned = AtomicBoolean(false)

    fun recordResponse(commandName: String, code: Int, data: ByteArray?, errorMsg: String? = null) {
        if (!OidbSniffer.isEnabled) return
        val key = watched.firstOrNull { commandName.contains(it) } ?: return
        val sequence = counts.merge(key, 1, Int::plus) ?: return
        if (sequence > MAX_RECORDS_PER_COMMAND) return
        val header = "${LocalDateTime.now().format(stampFormat)} cmd=$commandName code=$code " +
            "len=${data?.size ?: 0} err=${errorMsg ?: "-"} seq=$sequence"
        enqueue("$header\n${hex(data)}\n")
    }

    private fun enqueue(block: String) {
        if (!queue.offer(block)) return
        if (writerStarted.compareAndSet(false, true)) {
            Thread({ writeLoop() }, "qpet-protocol-probe").apply { isDaemon = true }.start()
        }
    }

    private fun writeLoop() {
        while (true) {
            val block = try {
                queue.take()
            } catch (_: InterruptedException) {
                continue
            }
            val dir = directory ?: continue
            try {
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "probe-${LocalDate.now()}.log")
                if (file.length() > MAX_FILE_BYTES) {
                    if (fileFullWarned.compareAndSet(false, true)) {
                        EngineLog.w(TAG, "协议抓包已到上限，本进程不再写入: ${file.name}")
                    }
                    continue
                }
                FileOutputStream(file, true).use { it.write(block.toByteArray()) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                EngineLog.w(TAG, "协议抓包写入失败: ${t.javaClass.simpleName}")
            }
        }
    }

    private fun hex(data: ByteArray?): String {
        if (data == null || data.isEmpty()) return "(empty)"
        val limit = minOf(data.size, MAX_HEX_BYTES)
        val builder = StringBuilder(limit * 2 + 64)
        for (index in 0 until limit) {
            val value = data[index].toInt()
            builder.append(HEX[(value shr 4) and 0xF]).append(HEX[value and 0xF])
            if ((index + 1) % (HEX_CHARS_PER_LINE / 2) == 0) builder.append('\n')
        }
        if (limit < data.size) builder.append("\n...(仅前 $limit 字节，共 ${data.size} 字节)")
        return builder.toString()
    }
}
