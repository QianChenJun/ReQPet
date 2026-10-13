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
                val cmd = chain.getArg(1) as? String
                val cmdInt = (chain.getArg(2) as? Number)?.toInt() ?: -1
                val subInt = (chain.getArg(3) as? Number)?.toInt() ?: -1
                if (isEnabled) {
                    try {
                        val body = chain.getArg(0) as? ByteArray
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
                // 回包记录：把 QQ 传入的真实 observer 包一层动态代理，透传同时记录回包内容。
                val observer = chain.getArg(4)
                val observerIfc = observer?.javaClass?.interfaces
                    ?.firstOrNull { it.name == "com.tencent.ergo.hostdelegate.pb.PetPbDelegate\$a" }
                if (isEnabled && observer != null && observerIfc != null) {
                    try {                        val wrapped = java.lang.reflect.Proxy.newProxyInstance(
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
                                    Log.i(
                                        "QPetTrace",
                                        "[oidb-resp] cmd=$cmd ($cmdInt/$subInt) code=$code len=${data?.size ?: 0} body=$hex"
                                    )
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
                        return@intercept chain.proceed(arrayOf(chain.getArg(0), chain.getArg(1), chain.getArg(2), chain.getArg(3), wrapped))
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        Log.w(TAG, "observer wrap failed: ${t.message}")
                    }
                } else if (isEnabled && observer != null) {
                    Log.i(TAG, "observer not interface: ${observer.javaClass.name} ifc=${observer.javaClass.interfaces.joinToString()}")
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
