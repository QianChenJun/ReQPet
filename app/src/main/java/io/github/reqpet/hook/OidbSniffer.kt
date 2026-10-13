package io.github.reqpet.hook

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue

/**
 * OIDB 信令嗅探：hook 宠物域发包代理的方法，把 QQ 宠物页面（含手Q UI 发起的 SVIP 礼包领取等）
 * 发起的所有 OIDB 请求与回包记录到 QPetTrace 与 traceFile，供协议逆向分析。
 * 仅在 key_debug_log 开启时安装，只读不拦截。
 *
 * 落盘是必要的：logcat 环形缓冲分钟级就会冲掉早先记录，而礼包领取这类每天只有一次的信令
 * 必须隔夜可查。
 */
object OidbSniffer {

    private const val TAG = "OidbSniffer"
    private const val TRACE_TAG = "QPetTrace"
    private const val MAX_BODY_DUMP = 4096
    private const val MAX_TRACE_BYTES = 2L * 1024 * 1024
    private const val QUEUE_CAPACITY = 4096

    @Volatile
    var isEnabled: Boolean = false

    /** 由 HookEntry 在安装前注入；为空时只写 logcat。 */
    @Volatile
    var traceFile: File? = null

    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)

    private fun trace(line: String) {
        Log.i(TRACE_TAG, line)
        queue.offer(line)
    }

    private fun startWriter(file: File) {
        val thread = Thread({
            val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            while (true) {
                val line = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    continue
                }
                try {
                    if (file.length() > MAX_TRACE_BYTES) file.delete()
                    BufferedWriter(FileWriter(file, true)).use { writer ->
                        writer.append(format.format(Date()))
                        writer.append(' ')
                        writer.append(line)
                        writer.append('\n')
                    }
                } catch (_: Throwable) {
                    // 落盘失败不影响宿主，仅丢失追踪记录
                }
            }
        }, "QPetTraceWriter")
        thread.isDaemon = true
        thread.start()
    }

    fun install(classLoader: ClassLoader, delegateClass: Class<*>, sendMethod: Method) {
        if (!isEnabled) return
        if (installed) return

        try {
            traceFile?.let { startWriter(it) }
            HookApi.hook(sendMethod).intercept { chain ->
                val cmd = chain.getArg(1) as? String
                val cmdInt = (chain.getArg(2) as? Number)?.toInt() ?: -1
                val subInt = (chain.getArg(3) as? Number)?.toInt() ?: -1
                if (isEnabled) {
                    try {
                        val body = chain.getArg(0) as? ByteArray
                        val hex = body?.let { bytesToHex(it.take(MAX_BODY_DUMP / 2)) } ?: ""
                        trace("[oidb] cmd=$cmd ($cmdInt/$subInt) len=${body?.size ?: 0} body=$hex")
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        Log.w(TAG, "sniff error: ${t.message}")
                    }
                }
                // 回包记录：把 QQ 传入的真实 observer 包一层动态代理，透传同时记录回包内容。
                val observer = chain.getArg(4)
                val observerIfc = observer?.javaClass?.interfaces
                    ?.firstOrNull { it.name == "com.tencent.ergo.hostdelegate.pb.PetPbDelegate\$a" }
                if (isEnabled && observer != null && observerIfc != null) {
                    try {
                        val wrapped = java.lang.reflect.Proxy.newProxyInstance(
                            observerIfc.classLoader,
                            arrayOf(observerIfc)
                        ) { _, method, args ->
                            if (method.name == "toString") return@newProxyInstance "OidbSnifferObserver"
                            if (method.name == "hashCode") return@newProxyInstance System.identityHashCode(observer)
                            if (method.name == "equals") return@newProxyInstance args?.getOrNull(0) === observer
                            if (args != null && args.isNotEmpty()) {
                                try {
                                    val code = (args[0] as? Number)?.toInt() ?: -1
                                    val data = args.getOrNull(1) as? ByteArray
                                    val hex = data?.let { bytesToHex(it.take(MAX_BODY_DUMP / 2)) } ?: ""
                                    trace("[oidb-resp] cmd=$cmd ($cmdInt/$subInt) code=$code len=${data?.size ?: 0} body=$hex")
                                } catch (t: Throwable) {
                                    if (t is kotlinx.coroutines.CancellationException) throw t
                                }
                            }
                            try {
                                method.invoke(observer, *args)
                            } catch (t: java.lang.reflect.InvocationTargetException) {
                                throw t.cause ?: t
                            }
                        }
                        return@intercept chain.proceed(
                            arrayOf(chain.getArg(0), chain.getArg(1), chain.getArg(2), chain.getArg(3), wrapped)
                        )
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        Log.w(TAG, "observer wrap failed: ${t.message}")
                    }
                }
                chain.proceed()
            }
            HookLog.log(TAG, "OIDB 嗅探已挂载: ${delegateClass.name}.${sendMethod.name}")
            installed = true
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            HookLog.e(TAG, "OIDB 嗅探挂载失败", t)
        }
    }

    private var installed: Boolean = false

    private fun bytesToHex(bytes: List<Byte>): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append("0123456789abcdef"[(b.toInt() shr 4) and 0xF])
            sb.append("0123456789abcdef"[b.toInt() and 0xF])
        }
        return sb.toString()
    }
}
