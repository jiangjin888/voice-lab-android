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
 * 调用 Kimi（Moonshot）对话接口，把一句中文口语解析成结构化任务信息。
 * 模型只输出 JSON，我们再解析成 KimiParse。
 */
object KimiClient {

    private val client = OkHttpClient.Builder().build()
    private const val URL = "https://api.moonshot.cn/v1/chat/completions"

    private val SYS_PROMPT = """
你是"实验记录助手"。用户会用中文说一句话，可能包含：实验任务、计划时间（如"明天上午9点"）、实验起止时间（如"从9点到11点"）。
今天是 {TODAY}。
请严格只输出一个 JSON 对象（不要任何解释，不要用 markdown 代码块）：
{"is_task": true/false, "title": "任务标题", "plan_time": "ISO8601日期时间，例如 2026-07-21T09:00:00，无法确定则填空字符串", "exp_start": "实验开始HH:mm或空", "exp_end": "实验结束HH:mm或空", "summary": "一句话总结"}
规则：
- 若明显是安排/提醒/实验/待办/计划，is_task 为 true；纯闲聊或记录则为 false；
- plan_time 必须是绝对日期时间（基于"今天"推算"明天/下周/下周一"），无法确定则填空；
- exp_start / exp_end 用 24 小时制 HH:mm。
""".trimIndent()

    /** 解析结果 */
    data class KimiParse(
        val isTask: Boolean,
        val title: String,
        val planTime: Long?,        // epoch millis
        val planTimeText: String?,  // 展示用
        val expStart: String?,      // HH:mm
        val expEnd: String?,        // HH:mm
        val summary: String?
    )

    /**
     * @param text  待解析的中文语句
     * @param today 形如 "2026-07-20"，用于帮助模型推算相对日期
     */
    suspend fun parse(ctx: Context, text: String, today: String): KimiParse {
        val key = Config.getKimiKey(ctx)
        if (key.isBlank()) throw Exception("未配置 Kimi API Key（点右上角设置填写）")

        val sys = SYS_PROMPT.replace("{TODAY}", today)
        val payload = JSONObject().apply {
            put("model", Config.getKimiModel(ctx))
            put("temperature", 0.3)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", sys))
                put(JSONObject().put("role", "user").put("content", "请解析这句话：$text"))
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

    private fun parseContent(content: String, fallbackText: String): KimiParse {
        // 去掉可能的 ```json 代码块包裹
        var s = content.trim()
        if (s.startsWith("```")) {
            s = s.substring(3)
            if (s.endsWith("```")) s = s.substring(0, s.length - 3)
            // 去掉首行可能的 "json"
            s = s.replaceFirst(Regex("^json\\s*", RegexOption.IGNORE_CASE), "")
            s = s.trim()
        }
        val j = JSONObject(s)
        val planIso = j.optString("plan_time", "").takeIf { it.isNotBlank() }
        val planTime = planIso?.let { parseIso(it) }
        return KimiParse(
            isTask = j.optBoolean("is_task", false),
            title = j.optString("title", "").ifBlank { fallbackText },
            planTime = planTime,
            planTimeText = planTime?.let { TimeParser.fmtPlan(it) },
            expStart = j.optString("exp_start", "").takeIf { it.isNotBlank() },
            expEnd = j.optString("exp_end", "").takeIf { it.isNotBlank() },
            summary = j.optString("summary", "").takeIf { it.isNotBlank() }
        )
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
