package com.voicelab.app.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置项（仅存本机 SharedPreferences，不上传）：
 *  - Kimi API Key / 模型
 *  - 语音识别引擎偏好：auto（系统优先，失败回退离线）/ system（仅系统）/ offline（仅 Vosk 离线）
 *  - 次日跟进已确认的任务 id 集合（避免重复弹窗）
 */
object Config {

    private const val PREF = "voicelab_config"
    private const val K_KIMI = "kimi_key"
    private const val K_KIMI_MODEL = "kimi_model"
    private const val K_ASR = "asr_engine"
    private const val K_FOLLOWUP = "followup_shown"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getKimiKey(c: Context): String = sp(c).getString(K_KIMI, "") ?: ""

    fun getKimiModel(c: Context): String =
        (sp(c).getString(K_KIMI_MODEL, "") ?: "").ifBlank { "moonshot-v1-8k" }

    fun setKimiKey(c: Context, v: String) = sp(c).edit().putString(K_KIMI, v).apply()

    fun setKimiModel(c: Context, v: String) = sp(c).edit().putString(K_KIMI_MODEL, v).apply()

    fun getAsrEngine(c: Context): String = sp(c).getString(K_ASR, "auto") ?: "auto"

    fun setAsrEngine(c: Context, v: String) = sp(c).edit().putString(K_ASR, v).apply()

    fun isFollowupShown(c: Context, id: Long): Boolean =
        sp(c).getStringSet(K_FOLLOWUP, emptySet())?.contains(id.toString()) ?: false

    fun markFollowupShown(c: Context, id: Long) {
        val set = HashSet(sp(c).getStringSet(K_FOLLOWUP, emptySet()) ?: emptySet())
        set.add(id.toString())
        sp(c).edit().putStringSet(K_FOLLOWUP, set).apply()
    }
}
