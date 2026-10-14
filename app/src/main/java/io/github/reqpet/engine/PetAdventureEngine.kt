package io.github.reqpet.engine

import android.content.Context
import io.github.reqpet.HookEntry
import io.github.reqpet.RuntimeSwitches
import io.github.reqpet.engine.config.TimeConfigManager
import io.github.reqpet.engine.model.*
import io.github.reqpet.engine.state.AccountSessionStore
import io.github.reqpet.engine.task.*
import io.github.reqpet.engine.utils.PetPureCalculations
import io.github.reqpet.protocol.QQPetDirectBridge
import io.github.reqpet.protocol.client.PetCareProtocolClient
import io.github.reqpet.ui.PreferencesHelper
import io.github.reqpet.ui.util.UiDescUtils
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import java.util.concurrent.atomic.AtomicLong

/**
 * Q宠后台全功能自动化调度引擎 (v1.0.75 架构解耦门面)
 * 采用 Round-Robin 时间片智能轮转算法，调度学业、打工、探险、自理、社交与竞技。
 */
class PetAdventureEngine(@Volatile private var bridge: QQPetDirectBridge) {

    companion object {
        private const val TAG = "PetAdventureEngine"
        private const val NETWORK_TIMEOUT_MS = 8000L

        @Volatile
        var sessionGeneration: Long = 1L

        @Volatile
        var cachedPetId: String? = null

        @Volatile
        var hasSyncedServerState: Boolean = false

        @Volatile
        var lastActiveStoryId: String? = null

        @Volatile
        var pendingSettlementStoryId: String? = null

        /**
         * 服务器回带、本地未追踪、且已尝试过结算的 story。玩家手动开的探险会以这种形式残留：
         * 服务器认为有未领取的任务而拒绝新的派遣（0x975e 回 135051），本地却没有它的追踪记录，
         * 结算永远不会触发。收编后只对每条 story 试一次，避免每轮重复发结算包。
         */
        private val settleProbedStoryIds: MutableSet<String> =
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

        /** 学园课程 story 形如 6100_<uuid>，与 PetStudyTask.startSchoolAwait 的 page=6100 对应。 */
        private const val STUDY_STORY_PREFIX = "6100_"
private const val PREFS_NAME = "qqpet_inproc_prefs"

        /**
         * 探险被服务端拒绝后的冷却截止时刻。0x975e 回 135051 表示服务端不接受这次派遣，
         * 实测会自愈（10-13 那次持续约 1.5 小时后恢复），但此前每轮都拿同一个错误重试；
         * 冷却期内只跑学习与打工，把探险的拒绝包省下来。
         */
        @Volatile
        var adventureRefusedUntilMillis: Long = 0L

        const val ADVENTURE_REFUSED_CODE = 135051
        // 30 分钟的冷却会把「学习与打工全档降效」的日子彻底拖成空转，改为 5 分钟：
        // 拒绝是暂时性的，短冷却既省包又能及时恢复探险。
        const val ADVENTURE_REFUSED_COOLDOWN_MS = 5 * 60 * 1000L

        /** 只对 135051 冷却，其它错误（网络抖动、超时）不占用冷却窗口。 */
        fun markAdventureRefused(code: Int): Boolean {
            if (code != ADVENTURE_REFUSED_CODE) return false
            val until = System.currentTimeMillis() + ADVENTURE_REFUSED_COOLDOWN_MS
            if (until <= adventureRefusedUntilMillis) return false
            adventureRefusedUntilMillis = until
            return true
        }

        fun adventureCooldownRemainingSeconds(): Long {
            val left = adventureRefusedUntilMillis - System.currentTimeMillis()
            return if (left <= 0L) 0L else (left + 999L) / 1000L
        }

        /**
         * 探险此刻是否真的能顶上：开关打开且不在 135051 冷却里。
         * 学习与打工的选档靠它判断「全档降效时还有没有别的出路」，有出路才避让。
         */
        fun isAdventureDispatchable(): Boolean = enableAdventure && adventureCooldownRemainingSeconds() <= 0L

        /** 每日礼包领取状态，按账号记 "日期|已试次数|done"；每天最多试 3 次。 */
        private const val GIFT_CLAIM_STATE_KEY = "key_gift_claim_state"
        private const val GIFT_CLAIM_MAX_ATTEMPTS = 3

        /** 已消耗学习道具的 story，保证一条课程只用一个。 */
        @Volatile
        var itemUsedStoryId: String? = null

        private val transactionSequence = AtomicLong()
        private val cycleSequence = AtomicLong()
        private val loopSequence = AtomicLong()

        @Volatile
        var currentTransactionId: Long = 0L
            private set

        @Volatile
        var currentCycleId: Long = 0L
            private set

        fun markStoryRecalled(storyId: String) {
            lastActiveStoryId = storyId
            pendingSettlementStoryId = storyId
            currentTaskEndTimeMillis = 0L
            lastReportedOngoingStoryId = null
            RuntimeDiagnostics.event(
                "task_recalled", "transaction" to currentTransactionId, "cycle" to currentCycleId,
                "generation" to sessionGeneration, "account" to RuntimeDiagnostics.id(currentActiveUin),
                "story_id" to RuntimeDiagnostics.id(storyId), "pending" to true
            )
        }

        fun clearSettledStory(storyId: String) {
            if (pendingSettlementStoryId == storyId) pendingSettlementStoryId = null
            if (lastActiveStoryId != storyId) return
            lastActiveStoryId = null
            lastReportedOngoingStoryId = null
            currentTaskEndTimeMillis = 0L
            RuntimeDiagnostics.event(
                "task_settled", "transaction" to currentTransactionId, "cycle" to currentCycleId,
                "generation" to sessionGeneration, "account" to RuntimeDiagnostics.id(currentActiveUin),
                "story_id" to RuntimeDiagnostics.id(storyId), "pending" to (pendingSettlementStoryId != null)
            )
        }

        private val sessionMutex = kotlinx.coroutines.sync.Mutex()

        @Volatile
        var lastReportedOngoingStoryId: String? = null

        @Volatile
        var currentActiveUin: String = ""

        @Volatile
        var isLoopRunning = false

        @Volatile
        var lastFatigueSwitchTimeMillis = 0L

        @Volatile
        var masterEnabled = false

        @Volatile
        var enableStudy = false

        @Volatile
        var enableWork = false

        @Volatile
        var enableCare = false

        @Volatile
        var enableOneClickCare = false

        @Volatile
        var enableAdventure = false

        @Volatile
        var enableSettle = false

        @Volatile
        var enableLikeBack = false

        @Volatile
        var enableClaimCoinBag = false

        @Volatile
        var enableFatigueToAdventure = false

        @Volatile
        var enableAutoPk = false

        @Volatile
        var lastPkTimeMillis = 0L

        @Volatile
        var pkCooldownMillis = 60 * 1000L

        @Volatile
        var prefHumanLikeSleep = true

        @Volatile
        var prefNightSleepMode = true

        @Volatile
        var prefScreenOffSilent = true

        @Volatile
        var prefActionToast = true

        @Volatile
        var prefDebugLog = false

        @Volatile
        var enableHireFriend = false

        @Volatile
        var prefHireFriendUinsCsv: String = ""

        @Volatile
        var prefPkBlacklistUinsCsv: String = ""

        @Volatile
        var prefHiredRecallProgress = 72

        @Volatile
        var enableActiveVisit = false

        @Volatile
        var prefActiveVisitFriends = true

        @Volatile
        var prefActiveVisitStrangers = true

        @Volatile
        var prefActiveVisitDailyLimit = 20

        @Volatile
        var lastActiveVisitTimeMillis = 0L

        @Volatile
        var cachedHireableFriends: List<QQPetDirectBridge.HireableFriend> = emptyList()

        @Volatile
        var enableFriendCare = false

        @Volatile
        var prefFriendCareEnergyThreshold = 60

        @Volatile
        var prefFriendCareCleanThreshold = 60

        @Volatile
        var currentStatusText = "全自动守护中 · 一刻不停三维轮转"

        @Volatile
        var currentTaskEndTimeMillis = 0L

        @Volatile
        var currentTaskTypeName = "进阶修习中"

        @Volatile
        var roundRobinCursor = 0

        @Volatile
        var studyAttributeCursor = 0

        @Volatile
        var workJobCursor = 0

        @Volatile
        var prefStudyMode = 0

        @Volatile
        var prefWorkMode = 0

        @Volatile
        var prefCustomSchoolStage = 0

        @Volatile
        var prefCustomCourseSubject = 0

        @Volatile
        var prefCustomCourseDuration = 0

        @Volatile
        var prefAutoUseStudyItem = false

        @Volatile
        var prefAutoClaimGift = false

        @Volatile
        var prefCustomWorkType = 0

        @Volatile
        var prefCustomWorkDuration = 0

        @Volatile
        var prefCareEnergyThreshold = 60

        @Volatile
        var prefCareCleanThreshold = 60

        @Volatile
        var lastCareTimeMillis = 0L

        @Volatile
        var lastLikeBackTimeMillis = 0L

        @Volatile
        var lastCoinBagTimeMillis = 0L

        @Volatile
        var lastOwnPetCheckMillis = 0L

        @Volatile
        var lastMasterOffNoticeUin: String? = null

        @Volatile
        var cachedSchoolDetails: QQPetDirectBridge.SecondMapDetails? = null

        @Volatile
        var cachedSchoolCourses: List<QQPetDirectBridge.SelectEvent>? = null

        @Volatile
        var cachedWorkPlaces: QQPetDirectBridge.SecondMapDetails? = null

        @Volatile
        var cachedWorkJobs: List<QQPetDirectBridge.SelectEvent>? = null

        @Volatile
        var learnedStudySubEvent: Long? = null

        @Volatile
        var learnedStudyName: String? = null

        @Volatile
        var learnedWorkSubEvent: Long? = null

        @Volatile
        var learnedWorkName: String? = null

        private fun clearAccountBoundMemoryCache(context: Context) {
            AccountSessionStore.clearAccountBoundMemoryCache(context)
            PetCycleDispatcher.resetFailureCount()
            PetMaintenanceCoordinator.resetCareBackoff()
            cachedPetId = null
            hasSyncedServerState = false
            lastActiveStoryId = null
            pendingSettlementStoryId = null
            currentTaskEndTimeMillis = 0L
            lastReportedOngoingStoryId = null
            cachedSchoolDetails = null
            cachedSchoolCourses = null
            cachedWorkPlaces = null
            cachedWorkJobs = null
            learnedStudySubEvent = null
            learnedStudyName = null
            learnedWorkSubEvent = null
            learnedWorkName = null
            cachedHireableFriends = emptyList()
        }

        fun saveScopedPetId(context: Context, petId: String, runtimeUin: String = currentActiveUin) {
            if (runtimeUin != currentActiveUin ||
                !AccountSessionGuard.isValidUin(runtimeUin) ||
                !AccountSessionGuard.isPetIdBelongingToUin(petId, runtimeUin)
            ) return
            AccountSessionStore.saveScopedPetId(context, petId, runtimeUin)
            cachedPetId = petId
        }

        fun getLiveRemainingSeconds(): Long =
            (currentTaskEndTimeMillis - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L

        /** 日志统一出口；context 为历史参数，已无用 */
        fun sendLog(message: String) = EngineLog.i(message)

        fun sendLog(level: EngineLog.Level, message: String) = EngineLog.write(level, message)

        fun formatLiveStatusText(): String {
            if (!masterEnabled) return "总开关未开启 · 模块待命中"
            val sec = getLiveRemainingSeconds()
            if (sec <= 0L) return if (currentTaskEndTimeMillis > 0L) {
                "任务已修毕 · 正在自动结算收益..."
            } else currentStatusText
            return "$currentTaskTypeName · 剩余 ${PetPureCalculations.formatDuration(sec)}"
        }

    }

    private var loopJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun updateBridge(newBridge: QQPetDirectBridge) {
        this.bridge = newBridge
    }

    fun sendLog(message: String) = Companion.sendLog(message)

    fun sendLog(level: EngineLog.Level, message: String) = Companion.sendLog(level, message)

    fun sendReadySignal(context: Context) {
        sendLog("[内核连接] 发包引擎与代理已成功接驳就绪")
    }

    fun startBackgroundLoop(context: Context) {
        reloadConfig(context)
        if (!masterEnabled) {
            val uinKey = currentActiveUin.ifEmpty { "unbound" }
            if (lastMasterOffNoticeUin != uinKey) {
                lastMasterOffNoticeUin = uinKey
                sendLog("[总开关] 未开启，主循环不启动（默认关闭，请在设置页打开总开关）")
            }
            return
        }
        lastMasterOffNoticeUin = null
        if (isLoopRunning) return
        isLoopRunning = true
        launchLoop(context)
    }

    fun resumeBackgroundLoop(context: Context) {
        scope.launch {
            withAccountSession(context, "resume") { startBackgroundLoop(context) }
        }
    }

    fun stopBackgroundLoop() {
        isLoopRunning = false
        loopJob?.cancel()
        loopJob = null
    }

    fun wakeUpMasterCycle(context: Context) {
        reloadConfig(context)
        if (!masterEnabled) {
            stopBackgroundLoop()
            sendLog("[总开关] 已关闭，调度已停止（配置已保存）")
            return
        }
        isLoopRunning = true
        launchLoop(context)
        sendLog("[即刻唤醒] 外部指令触发，调度协程已重置并立即巡检")
    }

    private fun launchLoop(context: Context) {
        loopJob?.cancel()
        val loopId = loopSequence.incrementAndGet()
        var currentJob: Job? = null
        currentJob = scope.launch {
            RuntimeDiagnostics.event("loop_start", "loop" to loopId)
            try {
                while (isActive && isLoopRunning && masterEnabled) {
                    val delayMs = try {
                        executeMasterCycle(context, loopId)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        RuntimeDiagnostics.error("loop_error", t, "loop" to loopId)
                        EngineLog.e(TAG, "主循环异常: ${t.javaClass.simpleName}: ${t.message}")
                        15000L
                    }
                    val sleepStarted = RuntimeDiagnostics.nowMs()
                    DiagnosticsExporter.autoSnapshotDaily(context)
                    RuntimeDiagnostics.event("sleep_start", "loop" to loopId, "planned_ms" to delayMs)
                    try {
                        delay(delayMs.milliseconds)
                    } catch (t: CancellationException) {
                        RuntimeDiagnostics.event(
                            "sleep_cancelled", "loop" to loopId, "planned_ms" to delayMs,
                            "actual_ms" to (RuntimeDiagnostics.nowMs() - sleepStarted)
                        )
                        throw t
                    }
                    val actualMs = RuntimeDiagnostics.nowMs() - sleepStarted
                    RuntimeDiagnostics.event(
                        "wake", "loop" to loopId, "planned_ms" to delayMs,
                        "actual_ms" to actualMs, "late_ms" to (actualMs - delayMs).coerceAtLeast(0L)
                    )
                }
            } finally {
                RuntimeDiagnostics.event("loop_stop", "loop" to loopId, "cancelled" to !isActive)
                if (loopJob === currentJob) {
                    isLoopRunning = false
                }
            }
        }
        loopJob = currentJob
    }

    suspend fun <T> withAccountSession(context: Context, source: String, action: suspend () -> T): T? {
        val transactionId = transactionSequence.incrementAndGet()
        val requestedAt = RuntimeDiagnostics.nowMs()
        var acquiredAt = 0L
        var acquired = false
        var outcome = "cancelled"
        var errorType = "none"
        RuntimeDiagnostics.event("transaction_wait", "transaction" to transactionId, "source" to source)
        try {
            sessionMutex.lock()
            acquired = true
            acquiredAt = RuntimeDiagnostics.nowMs()
            currentTransactionId = transactionId
            val liveUin = verifyAndSyncAccountSessionLocked(context)
            RuntimeDiagnostics.event(
                "transaction_start", "transaction" to transactionId, "source" to source,
                "wait_ms" to (acquiredAt - requestedAt), "generation" to sessionGeneration,
                "account" to RuntimeDiagnostics.id(liveUin)
            )
            if (!AccountSessionGuard.isValidUin(liveUin)) {
                outcome = "account_unavailable"
                return null
            }
            val result = action()
            if (bridge.getCurrentRuntimeUin() != liveUin) {
                verifyAndSyncAccountSessionLocked(context)
                outcome = "account_changed"
                return null
            }
            outcome = "completed"
            return result
        } catch (t: Throwable) {
            if (t is CancellationException) {
                outcome = "cancelled"
            } else {
                outcome = "error"
                RuntimeDiagnostics.error("transaction_error", t, "transaction" to transactionId, "source" to source)
            }
            errorType = t.javaClass.simpleName
            throw t
        } finally {
            val finishedAt = RuntimeDiagnostics.nowMs()
            RuntimeDiagnostics.event(
                "transaction_end", "transaction" to transactionId, "source" to source,
                "outcome" to outcome, "error_type" to errorType,
                "wait_ms" to ((if (acquired) acquiredAt else finishedAt) - requestedAt),
                "hold_ms" to (if (acquired) finishedAt - acquiredAt else 0L)
            )
            if (acquired) {
                currentTransactionId = 0L
                sessionMutex.unlock()
            }
        }
    }

    suspend fun executeMasterCycle(context: Context, loopId: Long = 0L): Long =
        withAccountSession(context, "cycle") { executeMasterCycleLocked(context, loopId) } ?: 10_000L

    private suspend fun executeMasterCycleLocked(context: Context, loopId: Long = 0L): Long {
        val cycleId = cycleSequence.incrementAndGet()
        val startedAt = RuntimeDiagnostics.nowMs()
        var reason = "error"
        var sleepMs = 0L
        currentCycleId = cycleId
        RuntimeDiagnostics.event(
            "cycle_start", "cycle" to cycleId, "loop" to loopId, "transaction" to currentTransactionId,
            "generation" to sessionGeneration, "account" to RuntimeDiagnostics.id(currentActiveUin),
            "synced" to hasSyncedServerState, "pending" to (pendingSettlementStoryId != null)
        )
        try {
            reloadConfig(context)
            RuntimeDiagnostics.event(
                "cycle_config", "cycle" to cycleId, "transaction" to currentTransactionId,
                "master" to masterEnabled, "study" to enableStudy, "work" to enableWork,
                "adventure" to enableAdventure, "settle" to enableSettle, "care" to enableCare,
                "one_click_care" to enableOneClickCare,
                "pk" to enableAutoPk, "night_silent" to prefNightSleepMode,
                "screen_silent" to prefScreenOffSilent, "recall_percent" to prefHiredRecallProgress
            )
            if (!masterEnabled) {
                sendLog("[总开关] 未开启，本轮巡检跳过")
                return (60 * 1000L).also { sleepMs = it; reason = "master_off" }
            }
            if (hasSyncedServerState) {
                checkStealthWindows(context)?.let {
                    sleepMs = it
                    reason = "stealth"
                    return it
                }
            }
            if (!bridge.isReady) {
                HookEntry.globalBridge?.let { if (it.isReady) bridge = it }
                    ?: HookEntry.reconnectBridgeIfAvailable(context)
            }
            if (!bridge.isReady) {
                currentStatusText = "发包代理连接中..."
                sendLog("[挂起] QQ 内部发包代理尚未就绪，等待 10 秒...")
                return 10000L.also { sleepMs = it; reason = "proxy_unready" }
            }
            val petId = ensurePetIdLocked(context)
                ?: return 30_000L.also { sleepMs = it; reason = "pet_unavailable" }
            maybeClaimDailyGift(context)
            sendLog("[主循环] 正在查询外出状态")
            val story = queryStoryStatusAwait(petId)
            RuntimeDiagnostics.event(
                "story_status", "cycle" to cycleId, "transaction" to currentTransactionId,
                "code" to story.code, "status" to story.status, "remaining_s" to story.remaining,
                "total_s" to story.total, "start_timestamp" to story.startTimestamp,
                "story_id" to RuntimeDiagnostics.id(story.storyId),
                "state" to when {
                    story.isOngoing -> "ongoing"
                    story.isReadyToSettle -> "ready"
                    story.isIdle -> "idle"
                    else -> "unknown"
                }
            )
            if (story.isOngoing || story.isReadyToSettle || story.isIdle) {
                hasSyncedServerState = true
                TimeConfigManager.extractAndConfigureDuration(story)
            }
            if (story.code != 0) {
                sendLog("[主循环] 外出状态没查完 code=${story.code} ${story.bodyNote ?: ""}")
                performMaintenance(context, petId, story)
                return 8_000L.also { sleepMs = it; reason = "query_failed" }
            }
            if (story.isIdle) {
                sendLog("[主循环] 状态查询成功，当前没有进行中的外出")
            }
            if (!story.isOngoing && !story.isReadyToSettle && !story.isIdle) {
                sendLog(EngineLog.Level.WARN, "[主循环] 外出状态尚未确认，保留本地任务并等待 15 秒")
                performMaintenance(context, petId, story)
                return 15_000L.also { sleepMs = it; reason = "status_unknown" }
            }
            // 派发时缓存的到期时间来自时长配置推测，可能远大于这条 story 的真实时长。
            // 服务器已确认无进行中的外出时，该推测值必须让位，否则结算会被拦到推测点，
            // 期间既不结算也不派发（实测空转 23 分钟）。
            val reconciledEnd = PetStoryHandlers.reconcileIdleTaskEnd(
                story, lastActiveStoryId, currentTaskEndTimeMillis
            )
            if (reconciledEnd != currentTaskEndTimeMillis) {
                sendLog(
                    EngineLog.Level.WARN,
                    "[状态校核] 服务器已确认无进行中的外出，丢弃未复核的本地到期时间，立即进入结算"
                )
                currentTaskEndTimeMillis = reconciledEnd
            }
            // 服务器说「没有进行中的外出」时仍会回带最近一条 story（含已到期的手动任务）。
            // 这条 story 未领取会让服务器一直拒绝新派遣，而本地没有它的追踪记录、结算不会触发。
            if (!story.isOngoing && !story.isReadyToSettle) {
                val reported = story.storyId
                if (!reported.isNullOrEmpty() && reported != lastActiveStoryId &&
                    reported != pendingSettlementStoryId && settleProbedStoryIds.add(reported)
                ) {
                    sendLog(
                        EngineLog.Level.WARN,
                        "[状态校核] 服务器仍挂着一条已结束的 story ($reported)，先结算它以免阻塞新任务派遣"
                    )
                    pendingSettlementStoryId = reported
                    currentTaskEndTimeMillis = 0
                }
            }
            val hiredDecision = handleOngoingStory(context, petId, story)
            val justSettled = hiredDecision?.settled == true || handleStorySettlement(context, petId, story)
            performMaintenance(context, petId, story, forceCareCheck = justSettled)
            val rem = story.remaining ?: 0L
            if (story.isOngoing && hiredDecision?.hasRecalled != true) {
                maybeUseStudyItem(context, story.storyId)
                val kind = currentTaskTypeName.ifEmpty { "外出" }
                val outingSleep = if (hiredDecision != null && hiredDecision.nextSleepMillis > 0L) {
                    hiredDecision.nextSleepMillis
                } else {
                    // 长任务按 3~5 分钟切片中途复查：服务器可能提前召回或中途降效，
                    // 睡到本地推测的到期点会错过这些变化。每片至少间隔 180 秒，不违反 0x975a 限流。
                    StealthScheduler.calculateTaskSleepSeconds(rem, prefHumanLikeSleep, enableSlices = true) * 1000L
                }
                val waitMs = sleepForMaintenance(context, outingSleep)
                val waitNote =
                    if (waitMs < outingSleep) "按维护巡检提前到 ${waitMs / 1000L} 秒后再查" else "${waitMs / 1000L} 秒后到期结算"
                sendLog(
                    "[任务进行中] 仍在$kind，剩余 ${PetPureCalculations.formatDuration(rem)}，$waitNote，StoryID=${story.storyId ?: "无"}"
                )
                return waitMs.also { sleepMs = it; reason = "ongoing" }
            }
            if (!lastActiveStoryId.isNullOrEmpty()) {
                sendLog(
                    EngineLog.Level.WARN,
                    "[主循环] 尚有未确认结算的任务 (StoryID=$lastActiveStoryId)，等待结算完成再派遣新任务"
                )
                return 15_000L.also { sleepMs = it; reason = "settlement_pending" }
            }
            return dispatchNextTask(context, petId).also { sleepMs = it; reason = "dispatch" }
        } catch (t: Throwable) {
            reason = if (t is CancellationException) "cancelled" else "error"
            throw t
        } finally {
            RuntimeDiagnostics.event(
                "cycle_end", "cycle" to cycleId, "loop" to loopId, "transaction" to currentTransactionId,
                "generation" to sessionGeneration, "account" to RuntimeDiagnostics.id(currentActiveUin),
                "elapsed_ms" to (RuntimeDiagnostics.nowMs() - startedAt),
                "reason" to reason, "sleep_ms" to sleepMs,
                "story_id" to RuntimeDiagnostics.id(lastActiveStoryId),
                "pending_id" to RuntimeDiagnostics.id(pendingSettlementStoryId),
                "task_end_ms" to currentTaskEndTimeMillis
            )
            currentCycleId = 0L
        }
    }

    /**
     * 每日礼包（SVIP 礼包）自动领取。礼包每天只放开一次，且是否可领由服务端判定：
     * 本地只保证「当天试过就不再试」，失败最多试 3 次，避免整点空转刷同一个拒绝。
     * 请求按抓包复刻（0x9bb1_0 入口上报 + 0x994c_1 kind=4），成败只看服务端 code。
     */
    private suspend fun maybeClaimDailyGift(context: Context) {
        if (!prefAutoClaimGift) return
        val today = java.time.LocalDate.now().toString()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stateKey = "${GIFT_CLAIM_STATE_KEY}_${currentActiveUin ?: "anon"}"
        val recorded = prefs.getString(stateKey, null)?.split("|")
        val sameDay = recorded?.getOrNull(0) == today
        val attempts = if (sameDay) recorded?.getOrNull(1)?.toIntOrNull() ?: 0 else 0
        if (sameDay && recorded?.getOrNull(2) == "done") return
        if (attempts >= GIFT_CLAIM_MAX_ATTEMPTS) return
        prefs.edit().putString(stateKey, "$today|${attempts + 1}|pending").apply()
        sendLog("[每日礼包] 尝试自动领取 (kind=${PetCareProtocolClient.GIFT_KIND_DAILY}, 第 ${attempts + 1} 次)")
        val (code, err) = PetCareTask.claimDailyGiftAwait(bridge)
        RuntimeDiagnostics.event(
            "gift_claim", "code" to code, "attempt" to (attempts + 1), "err" to (err ?: "-")
        )
        if (code == 0) {
            prefs.edit().putString(stateKey, "$today|${attempts + 1}|done").apply()
            sendLog("[每日礼包] 自动领取已发出，服务端 code=0")
        } else {
            sendLog(EngineLog.Level.WARN, "[每日礼包] 领取未成功 (code=$code, err=${err ?: "无"})，稍后再试")
        }
    }

    /**
     * 学习道具按「一条 story 一个」使用：模块自己报名的课在报名成功那一刻用掉，手动在游戏里
     * 上课、或模块处于疲劳避让期时在这里补齐；同一条 story 只用一次，避免每轮巡检重复消耗。
     */
    private suspend fun maybeUseStudyItem(context: Context, storyId: String?) {
        if (!prefAutoUseStudyItem) return
        if (storyId == null || !storyId.startsWith(STUDY_STORY_PREFIX)) return
        // 进程重启会清空内存标记，同日同一条 story 必须靠落盘记录避免重复消耗道具。
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val usedKey = "${PreferencesHelper.KEY_ITEM_USED_STORY}_${currentActiveUin ?: "anon"}"
        if (itemUsedStoryId == storyId || prefs.getString(usedKey, null) == storyId) {
            sendLog(EngineLog.Level.DEBUG, "[学习道具] 本条 story 已使用过道具，跳过 (StoryID=$storyId)")
            // 上课期间周期性采样库存：QQ 自身也会消耗道具，只有对照采样才能定位剩余数量字段。
            probeItemInventory("skip")
            return
        }
        sendLog("[学习道具] 为本条 story 使用道具 (StoryID=$storyId)")
        val (code, err) = PetCareTask.useStudyItemAwait(bridge)
        RuntimeDiagnostics.event("study_item", "code" to code, "story_id" to RuntimeDiagnostics.id(storyId))
        if (code == 0) {
            itemUsedStoryId = storyId
            prefs.edit().putString(usedKey, storyId).apply()
            sendLog("[学习道具] 上课期间使用一个学习道具 (StoryID=$storyId)")
            probeItemInventory("after_use")
        } else {
            sendLog(EngineLog.Level.WARN, "[学习道具] 使用未成功 (code=$code, err=${err ?: "无"})，下轮再试")
        }
    }

    /**
     * 采样道具库存：QQ 自己也会周期消耗道具，只有留下「使用前后 / 上课期间」的原始回包，
     * 才能离线比对出剩余数量字段（字段语义未确认前不做猜测解析）。
     */
    private suspend fun probeItemInventory(reason: String) {
        val (code, raw) = PetCareTask.queryItemInventoryAwait(bridge)
        RuntimeDiagnostics.event(
            "item_inventory", "reason" to reason, "code" to code, "len" to (raw?.size ?: 0)
        )
    }

    private fun checkStealthWindows(context: Context): Long? =
        EngineGates.checkStealthWindows(context, currentTaskEndTimeMillis)

    suspend fun ensurePetId(context: Context): String? =
        withAccountSession(context, "ensure_pet") { ensurePetIdLocked(context) }

    private suspend fun ensurePetIdLocked(context: Context): String? {
        val validMemoryId = cachedPetId?.takeIf {
            AccountSessionGuard.isPetIdBelongingToUin(it, currentActiveUin)
        }
        if (validMemoryId != null) {
            return validMemoryId
        } else if (cachedPetId != null) {
            sendLog(EngineLog.Level.WARN, "[会话安全] 内存宠物 ID 与当前 UIN($currentActiveUin) 不匹配，强制清空重拉")
            cachedPetId = null
        }

        val prefs = context.getSharedPreferences("qqpet_inproc_prefs", Context.MODE_PRIVATE)
        val resolved = AccountSessionGuard.resolveActivePetId(
            currentRuntimeUin = currentActiveUin,
            memoryPetId = cachedPetId,
            scopedSavedPetId = prefs.getString(
                AccountSessionGuard.scopedKey("key_cached_pet_id", currentActiveUin),
                null
            ),
            legacySavedPetId = prefs.getString("key_cached_pet_id", null)
        )
        if (!resolved.isNullOrEmpty()) {
            cachedPetId = resolved
            return resolved
        }

        val (_, fetched) = queryOwnPetAwait()
        if (fetched.isNullOrEmpty()) {
            sendLog(EngineLog.Level.ERROR, "[巡检] 获取宠物 ID 失败，30 秒后重试")
            return null
        }
        saveScopedPetId(context, fetched, currentActiveUin)
        return fetched.also { sendLog("[巡检] 成功锁定宠物 ID: $it") }
    }

    private suspend fun handleOngoingStory(
        context: Context, petId: String, story: StoryStatusResult
    ): PetHiredRecallTask.HiredMonitorDecision? =
        PetStoryHandlers.handleOngoingStory(context, bridge, petId, story)

    private suspend fun handleStorySettlement(context: Context, petId: String, story: StoryStatusResult): Boolean =
        PetStoryHandlers.handleStorySettlement(context, bridge, petId, story)

    private suspend fun performMaintenance(
        context: Context, petId: String, story: StoryStatusResult, forceCareCheck: Boolean = false
    ) {
        val startedAt = RuntimeDiagnostics.nowMs()
        var outcome = "completed"
        RuntimeDiagnostics.event(
            "maintenance_start", "cycle" to currentCycleId, "transaction" to currentTransactionId,
            "force_care" to forceCareCheck
        )
        try {
            PetMaintenanceCoordinator.performMaintenance(context, bridge, petId, story, forceCareCheck)
        } catch (t: Throwable) {
            outcome = if (t is CancellationException) "cancelled" else "error"
            throw t
        } finally {
            RuntimeDiagnostics.event(
                "maintenance_end", "cycle" to currentCycleId, "transaction" to currentTransactionId,
                "elapsed_ms" to (RuntimeDiagnostics.nowMs() - startedAt), "outcome" to outcome
            )
        }
    }

    /** 在途期间只为可执行的维护项目唤醒，不为 PK 唤醒。 */
    private fun sleepForMaintenance(context: Context, outingSleep: Long): Long {
        val maintenanceWait = io.github.reqpet.engine.task.PetMaintenanceCoordinator.millisUntilNextCheck(
            context, isOuting = true
        )
        val selected = minOf(outingSleep, maintenanceWait).coerceAtLeast(3_000L)
        RuntimeDiagnostics.event(
            "sleep_selection", "cycle" to currentCycleId, "transaction" to currentTransactionId,
            "outing_ms" to outingSleep, "maintenance_ms" to maintenanceWait, "selected_ms" to selected
        )
        return selected
    }

    private suspend fun dispatchNextTask(context: Context, petId: String): Long =
        PetCycleDispatcher.dispatchNextAction(context, bridge, petId)

    fun runAction(context: Context, action: String) {
        if (!masterEnabled) {
            sendLog("[总开关] 未开启，忽略手动指令：$action")
            return
        }
        scope.launch {
            withAccountSession(context, "manual") {
                RuntimeDiagnostics.event("manual_action", "transaction" to currentTransactionId, "action" to action)
                val petId = ensurePetIdLocked(context) ?: return@withAccountSession
                when (action) {
                    "cycle" -> executeMasterCycleLocked(context)
                    "query_work_places", "query_account_status" -> {
                        PetPreloader.preloadAccountDataAwait(bridge, petId)
                    }

                    else -> PetCycleDispatcher.executeAction(context, bridge, petId, action)
                }
            }
        }
    }

    suspend fun queryOwnPetAwait(timeoutMs: Long = NETWORK_TIMEOUT_MS): Pair<Int, String?> =
        PetEngineApi.queryOwnPetAwait(bridge, timeoutMs)

    suspend fun queryStoryStatusAwait(petId: String, timeoutMs: Long = NETWORK_TIMEOUT_MS): StoryStatusResult =
        PetWorkTask.queryStoryStatusAwait(bridge, petId, timeoutMs)

    suspend fun startAdventureAwait(petId: String, timeoutMs: Long = NETWORK_TIMEOUT_MS): Pair<Int, String?> =
        PetEngineApi.startAdventureAwait(bridge, petId, timeoutMs)

    /** 预加载学园 / 职业小镇数据到进程内缓存 */
    fun preloadAccountData(context: Context) {
        scope.launch {
            withAccountSession(context, "preload") {
                if (cachedWorkPlaces != null && cachedSchoolDetails != null) return@withAccountSession
                val petId = ensurePetIdLocked(context) ?: return@withAccountSession
                PetPreloader.preloadAccountDataAwait(bridge, petId)
            }
        }
    }

    suspend fun preloadAccountDataAwait(petId: String) = PetPreloader.preloadAccountDataAwait(bridge, petId)

    private fun verifyAndSyncAccountSessionLocked(context: Context): String {
        val liveUin = bridge.getCurrentRuntimeUin()
        val nextUin = liveUin.takeIf { AccountSessionGuard.isValidUin(it) }.orEmpty()
        if (nextUin != currentActiveUin) {
            sendLog("[会话变更] ${currentActiveUin.ifEmpty { "未绑定" }} -> ${nextUin.ifEmpty { "已登出" }}，重置会话与缓存")
            val previousGeneration = sessionGeneration
            sessionGeneration++
            RuntimeDiagnostics.event(
                "session_changed", "transaction" to currentTransactionId,
                "previous_generation" to previousGeneration, "generation" to sessionGeneration,
                "previous_account" to RuntimeDiagnostics.id(currentActiveUin),
                "account" to RuntimeDiagnostics.id(nextUin),
                "reason" to (if (nextUin.isEmpty()) "account_unavailable" else "account_changed")
            )
            bridge.channel.invalidateSession(
                previousGeneration, if (nextUin.isEmpty()) "account_unavailable" else "account_changed"
            )
            clearAccountBoundMemoryCache(context)
            lastMasterOffNoticeUin = null
            currentActiveUin = nextUin
        }
        if (nextUin.isEmpty()) {
            sendLog(EngineLog.Level.WARN, "[会话安全] 宿主登录态尚未就绪，跳过账号事务")
        }
        return liveUin
    }

    suspend fun queryPetAttributesAwait(petId: String, isSelf: Boolean = true): QQPetDirectBridge.PetAttributes? =
        PetEngineApi.queryPetAttributesAwait(bridge, petId, isSelf)

    suspend fun querySelectEventsAwait(
        page: Long,
        petId: String,
        schoolStage: Int = 0,
        careerType: Int = 0
    ): Pair<Int, List<QQPetDirectBridge.SelectEvent>> =
        PetEngineApi.querySelectEventsAwait(bridge, page, petId, schoolStage, careerType)

    suspend fun fetchAllHireableFriendsAwait(
        context: Context,
        enrichSelectedAndTop: Boolean = true
    ): List<QQPetDirectBridge.HireableFriend> =
        PetEngineApi.fetchAllHireableFriendsAwait(bridge, context, currentActiveUin, enrichSelectedAndTop)

    suspend fun enrichFriendDetailsAwait(friend: QQPetDirectBridge.HireableFriend): QQPetDirectBridge.HireableFriend =
        PetEngineApi.enrichFriendDetailsAwait(bridge, friend)

    suspend fun fetchLikeListAwait(extra: String = ""): Pair<Int, List<QQPetDirectBridge.LikeMember>> =
        PetEngineApi.fetchLikeListAwait(bridge, extra)

    private var lastConfigEcho: String? = null

    fun reloadConfig(context: Context) {
        EngineConfigLoader.reload(context, onDebugLogChanged = { prefDebugLog = it })
        val echo = EngineConfigLoader.runtimeEcho()
        if (echo != lastConfigEcho) {
            lastConfigEcho = echo
            sendLog(EngineLog.Level.INFO, "[配置] $echo")
        }
    }

    fun logConfigSummary(context: Context) = EngineConfigLoader.logSummary(context)
}
