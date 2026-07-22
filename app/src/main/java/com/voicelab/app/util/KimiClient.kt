package com.voicelab.app.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 调用 Kimi（Moonshot）对话接口：
 *  - parse：把用户一句话解析成结构化任务/实验列表（用于智能编排）
 *  - summarize：按用户自定义指令，把一天的实验记录整理成表格（用于今日总结）
 *  - testConnection：检测 Key 是否有效、接口是否可用
 * 模型仅输出 JSON，我们解析成对应结构。
 */
object KimiClient {

    private val client = OkHttpClient.Builder().build()
    private const val URL = "https://api.moonshot.cn/v1/chat/completions"

    private val SYS_PROMPT = """
你是"实验记录助手"。用户会用中文口语描述今天的实验/任务安排，可能包含多个条目。
今天是 {TODAY}。
请把用户输入解析成一个 JSON 数组（不要任何解释，不要用 markdown 代码块）。
每个元素字段：
{
  "is_task": true/false,
  "title": "任务/实验标题（一句话）",
  "plan_time": "ISO8601日期时间，例如 2026-07-21T09:00:00；无法确定则填空字符串",
  "exp_start": "实验开始 HH:mm 或空",
  "exp_end": "实验结束 HH:mm 或空",
  "summary": "一句话总结",
  "tags": ["标签1", "标签2"]  // 可选，例如 ["细胞培养", "PCR"]
}
规则：
- 若明显是安排/提醒/实验/待办/计划，is_task 为 true；纯闲聊或记录则为 false；
- plan_time 必须是绝对日期时间（基于"今天"推算"明天/下周/下周一"），无法确定则填空；
- exp_start / exp_end 用 24 小时制 HH:mm；
- 一句话里若提到多个任务，必须拆成多个 JSON 对象返回数组；
- 若用户只说了一件事，返回只含一个对象的数组。
""".trimIndent()

    private val SUM_SYS_PROMPT = """
你是实验记录整理助手，要把一天的实验语音/文字记录整理成一个 Excel 风格的表格。
请输出一个 JSON 对象（不要解释，不要 markdown 代码块）：
{
  "title": "表格标题",
  "columns": ["列名1", "列名2", "列名3", ...],
  "rows": [ ["单元格", "单元格", "单元格"], ... ]
}
要求：
- columns 是列名数组，rows 中每个子数组长度必须与 columns 长度一致；
- 列数、内容完全按用户的总结要求来；
- 只输出 JSON。
""".trimIndent()

    /** 解析结果（一句话拆成多条任务/实验） */
    data class KimiParse(
        val isTask: Boolean,
        val title: String,
        val planTime: Long?,        // epoch millis
        val planTimeText: String?,  // 展示用
        val expStart: String?,      // HH:mm
        val expEnd: String?,        // HH:mm
        val summary: String?,
        val tags: List<String>
    )

    /** 今日总结结果 */
    data class SummaryResult(
        val title: String,
        val columns: List<String>,
        val rows: List<List<String>>
    )

    // ---------------- parse：一句话 → 多条任务 ----------------
    suspend fun parse(ctx: Context, text: String, today: String): List<KimiParse> {
        val key = Config.getKimiKey(ctx)
        if (key.isBlank()) throw Exception("未配置 Kimi API Key（点右上角设置填写）")

        val sys = SYS_PROMPT.replace("{TODAY}", today)
        val payload = JSONObject().apply {
            put("model", Config.getKimiModel(ctx))
            put("temperature", 0.3)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sys))
                put(JSONObject().put("role", "user").put("content", "请解析这些话：$text"))
            })
        }
        val content = postAndExtract(ctx, key, payload)
        return parseContent(content, text)
    }

    // ---------------- summarize：记录 → 表格 ----------------
    suspend fun summarize(ctx: Context, recordsText: String, instruction: String, today: String): SummaryResult {
        val key = Config.getKimiKey(ctx)
        if (key.isBlank()) throw Exception("未配置 Kimi API Key（点右上角设置填写）")

        val userMsg = (if (instruction.isNotBlank())
            "用户的总结要求：$instruction\n\n"
        else
            "默认要求：整理出【做了什么实验 / 是否完成 / 实验时间段(几点到几点)】三列。\n\n") +
            "今天是 $today，实验记录如下：\n$recordsText"

        val payload = JSONObject().apply {
            put("model", Config.getKimiModel(ctx))
            put("temperature", 0.3)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SUM_SYS_PROMPT))
                put(JSONObject().put("role", "user").put("content", userMsg))
            })
        }
        val content = postAndExtract(ctx, key, payload)
        return parseSummary(content)
    }

    // ---------------- testConnection：检测 Key 是否有效 ----------------
    suspend fun testConnection(ctx: Context): Boolean {
        val key = Config.getKimiKey(ctx)
        if (key.isBlank()) return false
        val payload = JSONObject().apply {
            put("model", Config.getKimiModel(ctx))
            put("temperature", 0.1)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", "回复一个字：好"))
            })
        }
        return try {
            postAndExtract(ctx, key, payload).isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    // ---------------- 内部：发请求并取出回答文本 ----------------
    private suspend fun postAndExtract(ctx: Context, key: String, payload: JSONObject): String {
        val mediaType = "application/json".toMediaType()
        val req = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(mediaType))
            .build()
        val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
        val body = resp.body?.string() ?: throw Exception("Kimi 接口空响应")
        if (!resp.isSuccessful) {
            throw Exception("Kimi API 错误 ${resp.code}：${body.take(200)}")
        }
        val j = JSONObject(body)
        return j.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }

    private fun parseContent(content: String, fallbackText: String): List<KimiParse> {
        var s = content.trim()
        if (s.startsWith("```")) {
            s = s.substring(3)
            if (s.endsWith("```")) s = s.substring(0, s.length - 3)
            s = s.replaceFirst(Regex("^json\\s*", RegexOption.IGNORE_CASE), "").trim()
        }
        // 去掉外层可能的对象包裹，只取数组
        if (s.startsWith("{") && !s.startsWith("[")) {
            s = tryExtractArray(s) ?: s
        }
        val arr = try {
            JSONArray(s)
        } catch (_: Exception) {
            try { JSONArray().put(JSONObject(s)) } catch (_: Exception) { JSONArray() }
        }
        val result = mutableListOf<KimiParse>()
        for (i in 0 until arr.length()) {
            val j = arr.getJSONObject(i)
            val planIso = j.optString("plan_time", "").takeIf { it.isNotBlank() }
            val planTime = planIso?.let { parseIso(it) }
            val tags = mutableListOf<String>()
            j.optJSONArray("tags")?.let { ta ->
                for (k in 0 until ta.length()) tags.add(ta.getString(k))
            }
            result.add(
                KimiParse(
                    isTask = j.optBoolean("is_task", false),
                    title = j.optString("title", "").ifBlank { fallbackText },
                    planTime = planTime,
                    planTimeText = planTime?.let { TimeParser.fmtPlan(it) },
                    expStart = j.optString("exp_start", "").takeIf { it.isNotBlank() },
                    expEnd = j.optString("exp_end", "").takeIf { it.isNotBlank() },
                    summary = j.optString("summary", "").takeIf { it.isNotBlank() },
                    tags = tags
                )
            )
        }
        return result
    }

    private fun parseSummary(content: String): SummaryResult {
        var s = content.trim()
        if (s.startsWith("```")) {
            s = s.substring(3)
            if (s.endsWith("```")) s = s.substring(0, s.length - 3)
            s = s.replaceFirst(Regex("^json\\s*", RegexOption.IGNORE_CASE), "").trim()
        }
        val obj = try { JSONObject(s) } catch (_: Exception) {
            // 有些模型会在对象外层再套引号，尝试抽取第一个 JSON 对象
            val i = s.indexOf("{"); val j = s.lastIndexOf("}")
            if (i >= 0 && j > i) JSONObject(s.substring(i, j + 1)) else JSONObject()
        }
        val title = obj.optString("title", "实验记录总结")
        val cols = mutableListOf<String>()
        obj.optJSONArray("columns")?.let { ca ->
            for (k in 0 until ca.length()) cols.add(ca.getString(k))
        }
        val rows = mutableListOf<List<String>>()
        obj.optJSONArray("rows")?.let { ra ->
            for (k in 0 until ra.length()) {
                val r = ra.getJSONArray(k)
                val row = mutableListOf<String>()
                for (m in 0 until r.length()) row.add(r.optString(m, ""))
                rows.add(row)
            }
        }
        return SummaryResult(title, cols, rows)
    }

    private fun tryExtractArray(s: String): String? {
        val i = s.indexOf("["); val j = s.lastIndexOf("]")
        return if (i >= 0 && j > i) s.substring(i, j + 1) else null
    }

    private fun parseIso(s: String): Long? {
        val fmts = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd"
        )
        for (f in fmts) {
            try {
                return SimpleDateFormat(f, Locale.US).parse(s)?.time
            } catch (_: Exception) { }
        }
        return null
    }
}
