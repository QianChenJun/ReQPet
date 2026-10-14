package io.github.reqpet.engine.utils

import io.github.reqpet.protocol.QQPetDirectBridge

/**
 * 事件目录（课程、岗位）的档位比价。
 *
 * 服务端在下发目录时就已经把降效后的数字算好了：当日额度用满后，被削的档位收益直接缩小
 * （实测 4 小时档 1108 变 831，学园 1 小时档 35 变 9），所以按「每小时收益」排序即可，
 * 不需要猜服务端的额度门槛。同一方向的可做档位全部降效时返回 null，由调用方按用户配置
 * 转去冒险，而不是硬报一个被削到 25% 的档位。
 */
internal object CatalogPicker {

    private val MARKDOWN_IMAGE = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
    private val MARKDOWN_LINK = Regex("\\[[^\\]]*\\]\\([^)]*\\)")
    private val NUMBER = Regex("\\d+")

    fun parseDurationMinutes(costTime: String): Int? {
        val text = costTime.trim()
        if (text.isEmpty()) return null
        val value = NUMBER.find(text)?.value?.toIntOrNull() ?: return null
        return when {
            text.contains("小时") -> value * 60
            text.contains("分") -> value
            else -> null
        }
    }

    /**
     * 收益文本里既有「智力+14」也有带图标链接的「![#20px #20px](…/1776409721409.png) 71」，
     * 所以先剥掉链接再取最后一个数字，否则会把图标 URL 里的时间戳当成收益。
     */
    fun parseRewardValue(reward: String): Int? {
        val stripped = MARKDOWN_LINK.replace(MARKDOWN_IMAGE.replace(reward, " "), " ")
        return NUMBER.findAll(stripped).lastOrNull()?.value?.toIntOrNull()
    }

    fun ratePerHour(event: QQPetDirectBridge.SelectEvent): Double? {
        val minutes = parseDurationMinutes(event.costTime) ?: return null
        if (minutes <= 0) return null
        val value = parseRewardValue(event.reward) ?: return null
        return value * 60.0 / minutes
    }

    /**
     * 在未降效的档位里挑每小时收益最高的一档；收益并列时按 cursor 轮换，避免永远只报同一门课。
     * [includeFatigued] 只在「全档降效且没有别的方向可去」时打开：宁可拿 25% 的降效档继续干，
     * 也不要让宠物整段时间什么都不做。
     */
    fun pickBestRate(
        events: List<QQPetDirectBridge.SelectEvent>,
        cursor: Int = 0,
        includeFatigued: Boolean = false
    ): QQPetDirectBridge.SelectEvent? {
        val scored = events.filter { includeFatigued || !it.isFatigued }.mapNotNull { event ->
            val rate = ratePerHour(event) ?: return@mapNotNull null
            event to rate
        }
        if (scored.isEmpty()) return null
        val top = scored.maxOf { it.second }
        val best = scored.filter { it.second >= top * 0.999 }
        return best[Math.floorMod(cursor, best.size)].first
    }

    /** 决策依据单行日志：档位=每小时收益（降效档位加感叹号）。 */
    fun describeRanking(events: List<QQPetDirectBridge.SelectEvent>): String =
        events.joinToString(" ") { event ->
            val rate = ratePerHour(event)
            val shown = if (rate == null) "?" else ((rate * 10).toInt() / 10.0).toString()
            val mark = if (event.isFatigued) "!" else ""
            "${event.costTime}:$shown$mark"
        }
}
