package com.voicelab.app.util

import com.voicelab.app.data.Sentence
import com.voicelab.app.data.TaskEntity
import java.util.Calendar

object ExportUtil {

    /** 生成带 UTF-8 BOM 的 CSV，Excel 可直接打开 */
    fun buildCsv(tasks: List<TaskEntity>, sentences: List<Sentence>): String {
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.appendLine("=== 任务与实验记录 ===")
        sb.appendLine("内容,计划时间,状态,实验开始,实验结束,来源,创建时间")
        val statusMap = mapOf("todo" to "待完成", "done" to "已完成", "fail" to "未完成")
        tasks.forEach { t ->
            sb.appendLine(
                listOf(
                    t.content,
                    t.scheduledText ?: "",
                    statusMap[t.status] ?: t.status,
                    t.expStart ?: "",
                    t.expEnd ?: "",
                    if (t.source == "voice") "语音" else "文字",
                    fmtTime(t.created)
                ).joinToString(",") { quote(it) }
            )
        }
        sb.appendLine()
        sb.appendLine("=== 全部语句记录 ===")
        sb.appendLine("时间,来源,内容")
        sentences.forEach { s ->
            sb.appendLine(
                listOf(
                    fmtTime(s.timestamp),
                    if (s.source == "voice") "语音" else "文字",
                    s.text
                ).joinToString(",") { quote(it) }
            )
        }
        return sb.toString()
    }

    private fun quote(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    private fun fmtTime(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return "%d-%02d-%02d %02d:%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DATE),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
    }
}
