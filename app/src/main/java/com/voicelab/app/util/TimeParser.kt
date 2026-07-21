package com.voicelab.app.util

import java.util.Calendar
import java.util.Date
import java.util.regex.Pattern

/**
 * 解析中文口语中的时间表达式，例如：
 *  "明天上午9点做细胞培养实验，从9点到11点"
 *  产出：scheduled(明天9:00)、expStart(09:00)、expEnd(11:00)、content(做细胞培养实验)
 */
object TimeParser {

    data class Result(
        val scheduled: Long?,
        val scheduledText: String?,
        val expStart: String?,
        val expEnd: String?,
        val content: String,
        val createTask: Boolean
    )

    private val WEEK_MAP = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6,
        '日' to 0, '天' to 0, '七' to 0
    )

    fun parse(text: String): Result {
        val base = Calendar.getInstance().apply {
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var daySet = false

        when {
            text.contains("大后天") -> { base.add(Calendar.DATE, 3); daySet = true }
            text.contains("后天")   -> { base.add(Calendar.DATE, 2); daySet = true }
            Regex("明天|明早|明晚").containsMatchIn(text) -> { base.add(Calendar.DATE, 1); daySet = true }
            Regex("今天|今儿").containsMatchIn(text)      -> { daySet = true }
        }

        val wm = Regex("(?:下)?(?:周|星期|礼拜)([一二三四五六日天七])").find(text)
        if (wm != null) {
            val target = WEEK_MAP[wm.groupValues[1][0]] ?: 0
            var diff = (target - base.get(Calendar.DAY_OF_WEEK) + 7) % 7 // 0..6
            diff = if (wm.value.startsWith("下")) diff + 7 else if (diff == 0) 7 else diff
            base.add(Calendar.DATE, diff)
            daySet = true
        }

        var hour: Int? = null
        var min = 0
        var hasTime = false
        var pm = false
        var amForce = false

        val mod = Regex("(早上|上午|清晨|凌晨|中午|下午|傍晚|晚上|夜里)").find(text)
        if (mod != null) {
            val m = mod.value
            if (m in listOf("下午", "傍晚", "晚上", "夜里")) pm = true
            if (m in listOf("早上", "上午", "清晨", "凌晨")) amForce = true
        }

        val tm = Regex("(\\d{1,2})[:：](\\d{1,2})").find(text)
        if (tm != null) {
            hour = tm.groupValues[1].toInt()
            min = tm.groupValues[2].toInt()
            hasTime = true
        } else {
            val hm = Regex("(\\d{1,2})\\s*[点点时]\\s*半?\\s*(\\d{1,2})?\\s*分?").find(text)
            if (hm != null) {
                hour = hm.groupValues[1].toInt()
                val after = text.substring(hm.range.first)
                min = if (after.contains("半") && hm.groupValues[2].isEmpty()) 30
                else if (hm.groupValues[2].isNotEmpty()) hm.groupValues[2].toInt() else 0
                hasTime = true
            }
        }

        if (hour != null) {
            var h = hour
            if (pm && h < 12) h += 12
            if (amForce && h == 12) h = 0
            base.set(Calendar.HOUR_OF_DAY, h)
            base.set(Calendar.MINUTE, min)
        } else if (daySet) {
            base.set(Calendar.HOUR_OF_DAY, 9)
            base.set(Calendar.MINUTE, 0)
        }

        val scheduled = if (daySet || hasTime) base.timeInMillis else null

        var expStart: String? = null
        var expEnd: String? = null
        val dm = Regex("从\\s*(\\d{1,2})[:：]?(\\d{1,2})?\\s*[点时分]?\\s*(?:到|至|-|~)\\s*(\\d{1,2})[:：]?(\\d{1,2})?\\s*[点时分]?").find(text)
        if (dm != null) {
            val h1 = dm.groupValues[1].toInt()
            val mi1 = if (dm.groupValues[2].isNotEmpty()) dm.groupValues[2].toInt() else 0
            val h2 = dm.groupValues[3].toInt()
            val mi2 = if (dm.groupValues[4].isNotEmpty()) dm.groupValues[4].toInt() else 0
            expStart = "%d:%02d".format(h1, mi1)
            expEnd = "%d:%02d".format(h2, mi2)
        }

        val content = cleanContent(text)
        val kw = Regex("实验|提醒|闹钟|记得|待办|要做|任务|测量|采样|培养|记录").containsMatchIn(text)
        val createTask = scheduled != null || kw || expStart != null
        val scheduledText = if (scheduled != null) fmtPlan(scheduled) else null

        return Result(scheduled, scheduledText, expStart, expEnd, content.ifEmpty { text }, createTask)
    }

    private fun cleanContent(text: String): String {
        var s = text
        s = s.replace(Regex("(大后天|后天|明天|明早|明晚|今天|今儿|早上|上午|清晨|中午|下午|傍晚|晚上|夜里|凌晨|周[一二三四五六日天七]|星期[一二三四五六日天七]|礼拜[一二三四五六日天七]|下周|下个星期|下礼拜)"), "")
        s = s.replace(Regex("(\\d{1,2})[:：](\\d{1,2})"), "")
        s = s.replace(Regex("(\\d{1,2})\\s*[点点时]\\s*半?\\s*(\\d{1,2})?\\s*分?"), "")
        s = s.replace(Regex("从\\s*[\\d一-龥]*\\s*(?:到|至|-|~)\\s*[\\d一-龥]*\\s*[点时分]?"), "")
        s = s.replace(Regex("(提醒我|设个闹钟|定个闹钟|闹钟|提醒|记得|待办|要做|帮我|请|我想|我要|去做|做一下)"), "")
        s = s.replace(Regex("[，。、,\\s]+"), " ").trim()
        return s
    }

    /** 把 epoch millis 格式化成展示串，如 "7月21日(周二) 09:00" */
    fun fmtPlan(t: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        val wd = arrayOf("日", "一", "二", "三", "四", "五", "六")[cal.get(Calendar.DAY_OF_WEEK) - 1]
        return "%d月%d日(周%s) %02d:%02d".format(
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DATE), wd,
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
    }
}
