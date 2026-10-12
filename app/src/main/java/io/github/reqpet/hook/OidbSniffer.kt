package io.github.reqpet.hook

import android.util.Log
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * OIDB 信令嗅探：hook PetPbDelegate 实现类的发包方法，把 QQ 宠物页面（含手Q UI 发起的
 * SVIP 礼包领取等）发起的所有 OIDB 请求命令名与请求体记录到 QPetTrace，供协议逆向分析。
 * 仅在 key_debug_log 开启时安装，只读不拦截。
 */
object OidbSniffer {

    private const val TAG = "OidbSniffer"
    private const val MAX_BODY_DUMP = 4096

    @Volatile
    var isEnabled: Boolean = false

    fun install(classLoader: ClassLoader, delegateClass: Class<*>, sendMethod: Method) {
        if (!isEnabled) return
        val alreadyHooked = runCatching {
            OidbSniffer::class.java.getDeclaredField("installed").apply { isAccessible = true }.getBoolean(this)
        }.getOrDefault(false)
        if (alreadyHooked) return

        try {
            HookApi.hook(sendMethod).intercept { chain ->
                if (isEnabled) {
                    try {
                        val body = chain.getArg(0) as? ByteArray
                        val cmd = chain.getArg(1) as? String
                        val cmdInt = (chain.getArg(2) as? Number)?.toInt() ?: -1
                        val subInt = (chain.getArg(3) as? Number)?.toInt() ?: -1
                        val hex = body?.let { bytesToHex(it.take(MAX_BODY_DUMP / 2)) } ?: ""
                        Log.i(
                            "QPetTrace",
                            "[oidb] cmd=$cmd ($cmdInt/$subInt) len=${body?.size ?: 0} body=$hex"
                        )
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        Log.w(TAG, "sniff error: ${t.message}")
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
