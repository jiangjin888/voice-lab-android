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
 * 调用 Kimi（Moonshot）对话接口，把用户输入的一句话解析成结构化任务/实验记录列表。
 * 模型只输出 JSON 数组，我们再解析成 List<KimiParse>。
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

    /** 解析结果 */
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

    /**
     * @param text  待解析的中文语句
     * @param today 形如 "2026-07-20"，用于帮助模型推算相对日期
     * @return 解析出的任务/实验列表（至少返回一个元素，非任务时 isTask=false）
     */
    suspend fun parse(ctx: Context, text: String, today: String): List<KimiParse> {
        val key = Config.getKimiKey(ctx)
        if (key.isBlank()) throw Exception("未配置 Kimi API Key（点右上角设置填写）")

        val sys = SYS_PROMPT.replace("{TODAY}", today)
        val payload = JSONObject().apply {
            put("model", Config.getKimiModel(ctx))
            put("temperature", 0.3)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sys))
                put(JSONObject().put("role", "user").put("content", "请解析这些话：$text"))
            })
        }
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
        val content = j.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        return parseContent(content, text)
    }

    private fun parseContent(content: String, fallbackText: String): List<KimiParse> {
        // 去掉可能的 ```json 代码块包裹
        var s = content.trim()
        if (s.startsWith("```")) {
            s = s.substring(3)
            if (s.endsWith("```")) s = s.substring(0, s.length - 3)
            s = s.replaceFirst(Regex("^json\\s*", RegexOption.IGNORE_CASE), "")
            s = s.trim()
        }

        val arr = try {
            JSONArray(s)
        } catch (_: Exception) {
            // 模型有时只返回单个对象，兼容处理
            JSONArray().put(JSONObject(s))
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
