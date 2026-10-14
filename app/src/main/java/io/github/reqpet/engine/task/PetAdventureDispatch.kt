package io.github.reqpet.engine.task

import android.content.Context
import io.github.reqpet.engine.EngineLog
import io.github.reqpet.engine.PetAdventureEngine
import io.github.reqpet.engine.RuntimeDiagnostics
import io.github.reqpet.engine.config.TimeConfigManager
import io.github.reqpet.hook.OidbSniffer
import io.github.reqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 探险调度：森林探险的发起与回包状态登记
 *
 * 从 PetCycleDispatcher 拆出，降低调度器单文件体积
 */
internal object PetAdventureDispatch {

    private val catalogProbed = AtomicBoolean(false)

    suspend fun dispatchAdventure(
        context: Context,
        bridge: QQPetDirectBridge,
        petId: String
    ): Boolean {
        PetAdventureEngine.currentStatusText = "森林探险启程中..."
        PetAdventureEngine.sendLog("[探险启程] 正在前往神秘森林发起探险巡航...")
        val adventureRes: Triple<Int, String?, String?>? = withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine<Triple<Int, String?, String?>> { cont ->
                bridge.startAdventure(petId) { code, storyId, _, errorMsg ->
                    if (cont.isActive) cont.resume(Triple(code, storyId, errorMsg))
                }
            }
        }
        val code = adventureRes?.first ?: -99
        val storyId = adventureRes?.second
        val errorMsg = adventureRes?.third
        if (code == 0 && !storyId.isNullOrEmpty()) {
            PetAdventureEngine.lastActiveStoryId = storyId
            PetAdventureEngine.currentTaskTypeName = "森林探险中"
            PetAdventureEngine.currentTaskEndTimeMillis =
                System.currentTimeMillis() + TimeConfigManager.getCurrentDuration("ADVENTURE") * 1000L
            PetAdventureEngine.currentStatusText = "正在神秘森林探险寻宝中"
            RuntimeDiagnostics.event(
                "task_started", "transaction" to PetAdventureEngine.currentTransactionId,
                "cycle" to PetAdventureEngine.currentCycleId, "kind" to "adventure",
                "story_id" to RuntimeDiagnostics.id(storyId),
                "task_end_ms" to PetAdventureEngine.currentTaskEndTimeMillis
            )
            PetAdventureEngine.sendLog("[探险成功] 顺利踏入神秘森林！StoryID: $storyId，奇遇宝藏探索中")
            return true
        }
        // 服务端拒绝时的 errorMsg 是判据（例如次数上限），只记 code 会丢掉它。
        val detail = if (errorMsg.isNullOrBlank()) "" else ", err=$errorMsg"
        PetAdventureEngine.sendLog(EngineLog.Level.WARN, "[探险回包] 森林探险启程未生效 (code=$code$detail)")
        if (PetAdventureEngine.markAdventureRefused(code)) {
            PetAdventureEngine.sendLog(
                EngineLog.Level.WARN,
                "[探险冷却] code=$code 表示服务端本轮不接受探险派遣，暂停探险 ${PetAdventureEngine.ADVENTURE_REFUSED_COOLDOWN_MS / 60000} 分钟，期间学习与打工照常"
            )
        }
        probeAdventureCatalog(bridge, petId)
        return false
    }

    /**
     * 拒绝原因之一是「今日冒险次数已达上限」，判据在冒险页目录回包里；
     * 每进程只探测一次，回包原文由 ProtocolProbe 落盘。
     */
    private suspend fun probeAdventureCatalog(bridge: QQPetDirectBridge, petId: String) {
        if (!OidbSniffer.isEnabled) return
        if (!catalogProbed.compareAndSet(false, true)) return
        for (page in listOf(6700L, 6701L)) {
            val probed: Triple<Int, Int, String?>? = withTimeoutOrNull(8000L) {
                suspendCancellableCoroutine<Triple<Int, Int, String?>> { cont ->
                    bridge.querySelectEvents(page, petId) { code, events, _, err ->
                        if (cont.isActive) cont.resume(Triple(code, events.size, err))
                    }
                }
            }
            PetAdventureEngine.sendLog(
                "[冒险目录] page=$page 回包 code=${probed?.first ?: -99} 条目=${probed?.second ?: 0} " +
                    "err=${probed?.third ?: "-"}"
            )
        }
    }
}
