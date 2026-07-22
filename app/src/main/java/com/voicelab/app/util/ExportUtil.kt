package com.voicelab.app.util

import com.voicelab.app.data.Sentence
import com.voicelab.app.data.TaskEntity
import java.util.Calendar

object ExportUtil {

    private val STATUS_MAP = mapOf("todo" to "未完成", "done" to "已完成", "fail" to "未完成")

    /** 生成带 UTF-8 BOM 的 CSV，Excel 可直接打开（保留兼容） */
    fun buildCsv(tasks: List<TaskEntity>, sentences: List<Sentence>): String {
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.appendLine("=== 任务与实验记录 ===")
        sb.appendLine("内容,计划时间,状态,实验开始,实验结束,来源,创建时间")
        tasks.forEach { t ->
            sb.appendLine(
                listOf(
                    t.content, t.scheduledText ?: "", STATUS_MAP[t.status] ?: t.status,
                    t.expStart ?: "", t.expEnd ?: "",
                    if (t.source == "voice") "语音" else "文字", fmtTime(t.created)
                ).joinToString(",") { quote(it) }
            )
        }
        sb.appendLine()
        sb.appendLine("=== 全部语句记录 ===")
        sb.appendLine("时间,来源,内容")
        sentences.forEach { s ->
            sb.appendLine(
                listOf(fmtTime(s.timestamp), if (s.source == "voice") "语音" else "文字", s.text)
                    .joinToString(",") { quote(it) }
            )
        }
        return sb.toString()
    }

    /**
     * 生成 Excel 可直接打开的 .xls（HTML 表格 + ms-excel MIME，WPS/Excel 兼容）。
     * 列：记录时间 / 做了什么实验 / 是否完成 / 实验时间段。
     */
    fun buildXls(tasks: List<TaskEntity>): String {
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append(
            "<html xmlns:o=\"urn:schemas-microsoft-com:office:office\" " +
                "xmlns:x=\"urn:schemas-microsoft-com:office:excel\"><head>" +
                "<meta charset=\"UTF-8\">" +
                "<style>table{border-collapse:collapse}td,th{border:1px solid #999;padding:4px 8px;" +
                "font-family:sans-serif}th{background:#dbe4ff}</style></head><body>"
        )
        sb.append("<h3>实验记录表</h3>")
        sb.append("<table><tr><th>记录时间</th><th>做了什么实验</th><th>是否完成</th><th>实验时间段</th></tr>")
        tasks.forEach { t ->
            val time = if (t.expStart != null || t.expEnd != null) "${t.expStart ?: ""} - ${t.expEnd ?: ""}" else ""
            sb.append(
                "<tr><td>${fmtTime(t.created)}</td><td>${esc(t.content)}</td>" +
                    "<td>${STATUS_MAP[t.status] ?: "未完成"}</td><td>$time</td></tr>"
            )
        }
        sb.append("</table></body></html>")
        return sb.toString()
    }

    /** 通用表格（用于今日总结）：columns + rows → .xls */
    fun buildXlsFromTable(title: String, columns: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append('\uFEFF')
        sb.append(
            "<html xmlns:o=\"urn:schemas-microsoft-com:office:office\" " +
                "xmlns:x=\"urn:schemas-microsoft-com:office:excel\"><head>" +
                "<meta charset=\"UTF-8\">" +
                "<style>table{border-collapse:collapse}td,th{border:1px solid #999;padding:4px 8px;" +
                "font-family:sans-serif}th{background:#dbe4ff}</style></head><body>"
        )
        sb.append("<h3>${esc(title)}</h3>")
        sb.append("<table><tr>")
        columns.forEach { sb.append("<th>${esc(it)}</th>") }
        sb.append("</tr>")
        rows.forEach { row ->
            sb.append("<tr>")
            row.forEach { sb.append("<td>${esc(it)}</td>") }
            sb.append("</tr>")
        }
        sb.append("</table></body></html>")
        return sb.toString()
    }

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    private fun quote(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    private fun fmtTime(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return "%d-%02d-%02d %02d:%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DATE),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
    }
}
