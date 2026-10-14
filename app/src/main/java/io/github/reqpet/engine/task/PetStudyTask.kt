package io.github.reqpet.engine.task

import io.github.reqpet.engine.PetAdventureEngine
import io.github.reqpet.engine.RuntimeDiagnostics
import io.github.reqpet.engine.TaskLogger
import io.github.reqpet.engine.model.StudyDispatchParam
import io.github.reqpet.engine.model.StudyDispatchResult
import io.github.reqpet.engine.utils.CatalogPicker
import io.github.reqpet.engine.utils.PetPureCalculations
import io.github.reqpet.protocol.QQPetDirectBridge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 负责学园学习调度。课程和阶段严格按用户配置，查询失败或没有匹配项时本轮停止。
 */
object PetStudyTask {

    private const val NETWORK_TIMEOUT_MS = 8000L

    suspend fun startSchoolAwait(
        bridge: QQPetDirectBridge,
        petId: String,
        courseName: String = "基础学园课程",
        page: Long = 6100L,
        subEventType: Long = 6101L,
        timeoutMs: Long = NETWORK_TIMEOUT_MS
    ): Triple<Int, String?, String?> =
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    bridge.startSchool(petId, courseName, page, subEventType) { code, storyId, _, errorMsg ->
                        if (cont.isActive) cont.resume(Triple(code, storyId, errorMsg))
                    }
                }
            } ?: Triple(-99, null, "网络响应超时")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Triple(-99, null, t.message)
        }

    suspend fun executeAdaptiveStudy(
        bridge: QQPetDirectBridge,
        petId: String,
        param: StudyDispatchParam,
        onLog: TaskLogger
    ): StudyDispatchResult {
        val targetStage = resolveTargetStage(bridge, petId, param.customSchoolStage)
        if (targetStage <= 0) {
            onLog.warn("[学园调度] 没有可用的学园阶段，本次不降到初级学园")
            return StudyDispatchResult(code = -4, errorMsg = "学园阶段未知")
        }
        onLog("[学园阶段] 锁定目标学园阶段: stage=$targetStage")

        val (evtCode, dynamicEvents) = PetWorkTask.querySelectEventsAwait(
            bridge,
            6100L,
            petId,
            schoolStage = targetStage,
            careerType = 0
        )
        if (evtCode != 0 || dynamicEvents.isEmpty()) {
            onLog.warn("[学园调度] 课程查询失败 code=$evtCode，停在阶段 $targetStage，不改去固定课程")
            return StudyDispatchResult(code = evtCode, errorMsg = "课程查询失败")
        }
        onLog("[课程拉取] 服务端返回 ${dynamicEvents.size} 门课程")
        val adventureAvailable = PetAdventureEngine.isAdventureDispatchable()
        val (targetCourse, pickReason) = selectCourse(dynamicEvents, param, adventureAvailable)
        onLog("[选课比价] $pickReason")
        if (targetCourse == null) {
            val fatigueTip = dynamicEvents.firstOrNull { it.isFatigued }?.eventTips
            RuntimeDiagnostics.event(
                "study_course",
                "fatigued" to true,
                "fatigue_tip" to !fatigueTip.isNullOrEmpty(),
                "sub_event" to 0,
                "cost" to "",
                "avoid" to param.enableFatigueToAdventure
            )
            if (param.enableFatigueToAdventure && adventureAvailable) {
                onLog("[疲惫避让] 学园可做档位都已降效，按设置转入森林探险")
                return StudyDispatchResult(code = -2, isFatigued = true, fatigueTip = fatigueTip)
            }
            onLog.warn("[学园调度] 阶段 $targetStage 没有可报名的档位，本次不报名")
            return StudyDispatchResult(code = -3, errorMsg = "没有可报名的档位")
        }
        RuntimeDiagnostics.event(
            "study_course",
            "fatigued" to targetCourse.isFatigued,
            "fatigue_tip" to !targetCourse.eventTips.isNullOrEmpty(),
            "sub_event" to targetCourse.subEventType,
            "cost" to targetCourse.costTime,
            "avoid" to false
        )
        onLog("[学园报名] 锁定课程: ${targetCourse.eventName} (时长:${targetCourse.costTime})，发起启程...")
        val (code, storyId, errMsg) = startSchoolAwait(
            bridge,
            petId,
            targetCourse.eventName,
            6100L,
            targetCourse.subEventType
        )
        if (code == 0 && !storyId.isNullOrEmpty()) {
            return StudyDispatchResult(
                code = 0,
                storyId = storyId,
                courseName = targetCourse.eventName,
                subEventType = targetCourse.subEventType
            )
        }
        if (PetPureCalculations.isPetAlreadyOutError(code, errMsg)) {
            onLog("[学园报名] 小宠已在出行中 (${errMsg ?: "code=$code"})")
        } else {
            onLog.warn("[学园调度] ${targetCourse.eventName} 报名未成功 (code=$code, err=${errMsg ?: "无"})，不改报其它课程")
        }
        return StudyDispatchResult(
            code = code,
            errorMsg = errMsg ?: "报名未生效",
            courseName = targetCourse.eventName,
            subEventType = targetCourse.subEventType
        )
    }

    private suspend fun resolveTargetStage(bridge: QQPetDirectBridge, petId: String, customStage: Int): Int {
        if (customStage > 0) return customStage
        val details = PetWorkTask.querySecondMapInfoDetailsAwait(bridge, 6100L, petId)
        return if (details.code == 0 && details.currentStage > 0) details.currentStage else 0
    }

    /**
     * 选课：先按用户设定的科目与时长档收敛，再在未降效的档位里比每小时收益。
     * 收敛结果全被降效时逐级放宽（先放宽时长、再放宽科目）。
     * 放宽后仍全部降效时，只有「转冒险」真的走得通（[adventureAvailable]）才返回 null 交给调用方避让，
     * 否则照拿最优的降效档继续上课，避免白天额度用满加探险冷却时整段空转。
     */
    private fun selectCourse(
        events: List<QQPetDirectBridge.SelectEvent>,
        param: StudyDispatchParam,
        adventureAvailable: Boolean
    ): Pair<QQPetDirectBridge.SelectEvent?, String> {
        val available = events.filter { it.canDo }.ifEmpty { events }
        if (available.isEmpty()) return null to "目录为空"
        val durationFiltered = when (param.customCourseDuration) {
            1 -> available.filter { it.costTime.contains("分") && !it.costTime.contains("90") && !it.costTime.contains("135") }
            2 -> available.filter { it.costTime.contains("小时") || it.costTime.contains("90") || it.costTime.contains("135") }
            else -> available
        }
        fun bySubject(source: List<QQPetDirectBridge.SelectEvent>) = when (param.customCourseSubject) {
            1 -> source.filter { it.reward.contains("智力") }
            2 -> source.filter { it.reward.contains("力量") }
            3 -> source.filter { it.reward.contains("魅力") }
            else -> source
        }
        val stages = listOf(
            "科目+时长" to bySubject(durationFiltered).ifEmpty { durationFiltered },
            "放宽时长" to bySubject(available),
            "放宽科目" to durationFiltered.ifEmpty { available }
        )
        for ((stageName, candidates) in stages) {
            val ranking = CatalogPicker.describeRanking(candidates)
            val picked = CatalogPicker.pickBestRate(candidates, param.studyAttributeCursor)
            if (picked != null) {
                return picked to "$stageName 选中 ${picked.eventName}｜$ranking"
            }
        }
        val ranking = CatalogPicker.describeRanking(available)
        if (param.enableFatigueToAdventure && adventureAvailable) {
            return null to "可做档位全部降效，转探险｜$ranking"
        }
        val fallback = CatalogPicker.pickBestRate(available, param.studyAttributeCursor, includeFatigued = true)
            ?: return null to "可做档位全部降效且无一档收益可解析｜$ranking"
        return fallback to "全档降效，无其它可用方向，按最优档继续 ${fallback.eventName}｜$ranking"
    }
}
