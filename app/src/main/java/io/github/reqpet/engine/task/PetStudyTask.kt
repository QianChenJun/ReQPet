package io.github.reqpet.engine.task

import io.github.reqpet.engine.TaskLogger
import io.github.reqpet.engine.model.StudyDispatchParam
import io.github.reqpet.engine.model.StudyDispatchResult
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

        if (param.hiddenCareerRush && param.hiddenCareerTarget in 0..3) {
            return executeHiddenCareerRush(bridge, petId, targetStage, param, onLog)
        }

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
        val targetCourse = filterAndSelectCourse(dynamicEvents, param)
        if (targetCourse == null) {
            onLog.warn("[学园调度] 阶段 $targetStage 没有与所设科目或时长匹配的课程，本次不报名")
            return StudyDispatchResult(code = -3, errorMsg = "没有与所设科目或时长匹配的课程")
        }
        if (param.enableFatigueToAdventure && targetCourse.isFatigued) {
            return StudyDispatchResult(code = -2, isFatigued = true, fatigueTip = targetCourse.eventTips)
        }
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

    /**
     * 隐藏职业速成：专攻单科 + 强制短课高频触发 + 疲劳照常上课（疲劳单次涨幅小、上课次数多，触发概率更高）。
     * 属性倍率日志：主属性相对其余两属性之和的倍数，参考 2~3 倍起出、运气差需 5~6 倍。
     *
     * 主属性选择：target=0 为智能模式——从 6101~6106 隐藏职业场所的锁定状态推断尚未解锁的职业对应属性，
     * 优先倍率最高（离解锁最近）者；解锁后场所 limitStatus 翻转即自动轮换到下一个。
     */
    private suspend fun executeHiddenCareerRush(
        bridge: QQPetDirectBridge,
        petId: String,
        targetStage: Int,
        param: StudyDispatchParam,
        onLog: TaskLogger
    ): StudyDispatchResult {
        val details = PetWorkTask.querySecondMapInfoDetailsAwait(bridge, 6100L, petId)
        val subject = resolveRushSubject(param.hiddenCareerTarget, details)
        if (subject == null) {
            onLog("[速成调度] 无法确定速成主属性（属性数据未知且未手动指定），本轮停止")
            return StudyDispatchResult(code = -3, errorMsg = "速成主属性未知")
        }
        if (details.code == 0 && details.power + details.intel + details.charm > 0L) {
            val (main, others, label) = when (subject) {
                1 -> Triple(details.intel, details.power + details.charm, "智力")
                2 -> Triple(details.power, details.intel + details.charm, "力量")
                else -> Triple(details.charm, details.power + details.intel, "魅力")
            }
            val ratio = if (others > 0L) main * 100L / others else 0L
            onLog("[速成监测] 主攻$label $label=$main 其余和=$others 倍率=${ratio / 100}.${(ratio % 100).toString().padStart(2, '0')} (参考 2~3 倍起出)")
        }

        val (evtCode, dynamicEvents) = PetWorkTask.querySelectEventsAwait(
            bridge,
            6100L,
            petId,
            schoolStage = targetStage,
            careerType = 0
        )
        if (evtCode != 0 || dynamicEvents.isEmpty()) {
            onLog.warn("[速成调度] 课程查询失败 code=$evtCode，本轮停止")
            return StudyDispatchResult(code = evtCode, errorMsg = "课程查询失败")
        }
        val subjectCourses = dynamicEvents.filter { it.reward.contains(subjectLabel(subject)) }
        if (subjectCourses.isEmpty()) {
            onLog.warn("[速成调度] 阶段 $targetStage 没有专攻科目课程，本轮停止")
            return StudyDispatchResult(code = -3, errorMsg = "没有专攻科目课程")
        }
        // 短课优先：时长最短者触发次数最多；无时长标注则按原顺序
        val targetCourse = subjectCourses.minByOrNull { parseCourseMinutes(it.costTime) } ?: subjectCourses.first()
        onLog("[速成报名] 锁定短课: ${targetCourse.eventName} (时长:${targetCourse.costTime})，发起启程...")
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
            onLog("[速成报名] 小宠已在出行中 (${errMsg ?: "code=$code"})")
        } else {
            onLog.warn("[速成调度] ${targetCourse.eventName} 报名未成功 (code=$code, err=${errMsg ?: "无"})")
        }
        return StudyDispatchResult(
            code = code,
            errorMsg = errMsg ?: "报名未生效",
            courseName = targetCourse.eventName,
            subEventType = targetCourse.subEventType
        )
    }

    private fun subjectLabel(subject: Int): String = when (subject) {
        1 -> "智力"
        2 -> "力量"
        else -> "魅力"
    }

    /**
     * 智能选科：手动指定（1~3）直接用；智能（0）时按倍率最高（主属性/其余两属性之和）选当前离解锁最近的属性。
     * 全部数据不可用时回退智力。
     */
    private fun resolveRushSubject(manualTarget: Int, details: QQPetDirectBridge.SecondMapDetails): Int {
        if (manualTarget in 1..3) return manualTarget
        if (details.code != 0 || details.power + details.intel + details.charm <= 0L) return 1
        val candidates = listOf(
            Triple(1, details.intel, details.power + details.charm),
            Triple(2, details.power, details.intel + details.charm),
            Triple(3, details.charm, details.power + details.intel)
        )
        return candidates.maxByOrNull { if (it.third > 0L) it.second * 1000L / it.third else Long.MIN_VALUE }?.first ?: 1
    }

    private fun parseCourseMinutes(costTime: String): Int {
        val num = costTime.filter { it.isDigit() }.toIntOrNull() ?: return Int.MAX_VALUE
        return when {
            costTime.contains("小时") -> num * 60
            else -> num
        }
    }

    private suspend fun resolveTargetStage(bridge: QQPetDirectBridge, petId: String, customStage: Int): Int {
        if (customStage > 0) return customStage
        val details = PetWorkTask.querySecondMapInfoDetailsAwait(bridge, 6100L, petId)
        return if (details.code == 0 && details.currentStage > 0) details.currentStage else 0
    }

    private fun filterAndSelectCourse(
        events: List<QQPetDirectBridge.SelectEvent>,
        param: StudyDispatchParam
    ): QQPetDirectBridge.SelectEvent? {
        val available = events.filter { it.canDo }.ifEmpty { events }
        if (available.isEmpty()) return null
        val durationFiltered = when (param.customCourseDuration) {
            1 -> available.filter { it.costTime.contains("分") && !it.costTime.contains("90") && !it.costTime.contains("135") }
            2 -> available.filter { it.costTime.contains("小时") || it.costTime.contains("90") || it.costTime.contains("135") }
            else -> available
        }
        if (durationFiltered.isEmpty()) return null
        return when (param.customCourseSubject) {
            1 -> durationFiltered.find { it.reward.contains("智力") }
            2 -> durationFiltered.find { it.reward.contains("力量") }
            3 -> durationFiltered.find { it.reward.contains("魅力") }
            else -> durationFiltered[param.studyAttributeCursor % durationFiltered.size]
        }
    }
}
