package io.github.reqpet.engine

import io.github.reqpet.engine.utils.CatalogPicker
import io.github.reqpet.protocol.QQPetDirectBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档位比价的纯计算用例。数字取自实测目录：服务端在额度用满后直接下发缩小后的收益
 * （学园 20 分钟档 14→4、打工 4 小时档 1108→831），因此比价必须按当下的目录算。
 */
class CatalogPickerTest {

    private fun event(
        name: String,
        costTime: String,
        reward: String,
        cost: String = "",
        fatigued: Boolean = false,
        canDo: Boolean = true
    ) = QQPetDirectBridge.SelectEvent(
        eventName = name,
        subEventType = 1L,
        canDo = canDo,
        cost = cost,
        costTime = costTime,
        reward = reward,
        isFatigued = fatigued
    )

    @Test
    fun durationParsesMinutesAndHours() {
        assertEquals(20, CatalogPicker.parseDurationMinutes("20分钟"))
        assertEquals(45, CatalogPicker.parseDurationMinutes("45分钟"))
        assertEquals(60, CatalogPicker.parseDurationMinutes("1小时"))
        assertEquals(240, CatalogPicker.parseDurationMinutes("4小时"))
        assertNull(CatalogPicker.parseDurationMinutes("不限"))
        assertNull(CatalogPicker.parseDurationMinutes(""))
    }

    @Test
    fun energyCostParsesFromWorkCost() {
        assertEquals(5, CatalogPicker.parseEnergyCost("体力5(当前65)，清洁2(当前65)"))
        assertEquals(40, CatalogPicker.parseEnergyCost("体力40(当前100)，清洁16(当前100)"))
        assertNull(CatalogPicker.parseEnergyCost("金币+18"))
    }

    @Test
    fun rewardIgnoresMarkdownIconTimestamp() {
        val withIcon = "![#20px #20px](https://tianquan.gtimg.cn/petRaise/item/1776409721409.png) 71"
        assertEquals(71, CatalogPicker.parseRewardValue(withIcon))
        assertEquals(14, CatalogPicker.parseRewardValue("智力+14"))
        assertNull(CatalogPicker.parseRewardValue("无"))
    }

    @Test
    fun rateIsRewardNormalizedPerHour() {
        assertEquals(42.0, CatalogPicker.ratePerHour(event("20分钟课", "20分钟", "智力+14"))!!, 0.01)
        assertEquals(35.0, CatalogPicker.ratePerHour(event("1小时课", "1小时", "智力+35"))!!, 0.01)
        assertEquals(277.0, CatalogPicker.ratePerHour(event("4小时工", "4小时", "工分+1108"))!!, 0.01)
        assertEquals(207.75, CatalogPicker.ratePerHour(event("4小时工", "4小时", "工分+831"))!!, 0.01)
    }

    @Test
    fun picksHighestRateAcrossTiers() {
        val picked = CatalogPicker.pickBestRate(
            listOf(
                event("20分钟课", "20分钟", "智力+14"),
                event("4小时工", "4小时", "工分+1108"),
                event("1小时课", "1小时", "智力+35")
            )
        )
        assertEquals("4小时工", picked?.eventName)
    }

    @Test
    fun automaticWorkSelectionPrefersRewardPerEnergy() {
        val picked = CatalogPicker.pickBestResourceRate(
            listOf(
                event("10分钟工", "10分钟", "18", cost = "体力5(当前100)"),
                event("45分钟工", "45分钟", "72", cost = "体力10(当前100)"),
                event("2小时工", "2小时", "171", cost = "体力25(当前100)"),
                event("4小时工", "4小时", "264", cost = "体力40(当前100)")
            )
        )
        assertEquals("45分钟工", picked?.eventName)
    }

    @Test
    fun fatiguedTierLosesToSmalleButUnreducedTier() {
        val picked = CatalogPicker.pickBestRate(
            listOf(
                event("4小时工", "4小时", "工分+831", fatigued = true),
                event("45分钟工", "45分钟", "工分+288")
            )
        )
        assertEquals("45分钟工", picked?.eventName)
    }

    @Test
    fun allTiersFatiguedYieldsNullSoCallerCanFallBackToAdventure() {
        val picked = CatalogPicker.pickBestRate(
            listOf(
                event("4小时工", "4小时", "工分+831", fatigued = true),
                event("1小时课", "1小时", "智力+9", fatigued = true)
            )
        )
        assertNull(picked)
    }

    @Test
    fun includeFatiguedKeepsWorkingWithTwentyFivePercentTiersWhenNothingElseIsLeft() {
        val picked = CatalogPicker.pickBestRate(
            listOf(
                event("4小时工", "4小时", "工分+831", fatigued = true),
                event("1小时课", "1小时", "智力+9", fatigued = true)
            ),
            includeFatigued = true
        )
        assertEquals("4小时工", picked?.eventName)
    }

    @Test
    fun includeFatiguedCommitsToTheBestRateAmongReducedTiers() {
        // 只在「同一方向已无未降效档位」时才走这条路，所以这里就是纯比价：
        // 4 小时档被削到 831 仍是 207.75/h，比 1 小时档被削到 9 强，继续干比空转好。
        val picked = CatalogPicker.pickBestRate(
            listOf(
                event("1小时课", "1小时", "智力+9", fatigued = true),
                event("4小时工", "4小时", "工分+831", fatigued = true)
            ),
            includeFatigued = true
        )
        assertEquals("4小时工", picked?.eventName)
    }

    @Test
    fun equalRatesRotateByCursor() {
        val tiers = listOf(
            event("甲", "20分钟", "智力+14"),
            event("乙", "20分钟", "智力+14")
        )
        assertEquals("甲", CatalogPicker.pickBestRate(tiers, 0)?.eventName)
        assertEquals("乙", CatalogPicker.pickBestRate(tiers, 1)?.eventName)
        assertEquals("甲", CatalogPicker.pickBestRate(tiers, 2)?.eventName)
    }

    @Test
    fun unparsableTierIsSkippedInsteadOfWinning() {
        val picked = CatalogPicker.pickBestRate(
            listOf(
                event("不限时长", "不限", "智力+14"),
                event("20分钟课", "20分钟", "智力+14")
            )
        )
        assertEquals("20分钟课", picked?.eventName)
    }

    @Test
    fun rankingMarksReducedTiers() {
        val text = CatalogPicker.describeRanking(
            listOf(
                event("4小时工", "4小时", "工分+831", fatigued = true),
                event("45分钟工", "45分钟", "工分+288")
            )
        )
        assertTrue("降效档位必须带感叹号，日志才能解释为什么没选它: $text", text.contains("4小时:207.7!"))
        assertTrue(text.contains("45分钟:384.0"))
    }
}
