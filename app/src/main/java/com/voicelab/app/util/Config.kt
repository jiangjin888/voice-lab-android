package com.voicelab.app.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置：Kimi(Moonshot) API Key 仅存在本机 SharedPreferences，不上传。
 * 语音识别已改为本地 Vosk，无需任何云端语音密钥。
 */
object Config {

    private const val PREF = "voicelab_config"
    private const val K_KIMI = "kimi_key"
    private const val K_KIMI_MODEL = "kimi_model"

    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getKimiKey(c: Context): String = sp(c).getString(K_KIMI, "") ?: ""

    fun getKimiModel(c: Context): String =
        (sp(c).getString(K_KIMI_MODEL, "") ?: "").ifBlank { "moonshot-v1-8k" }

    fun setKimiKey(c: Context, v: String) =
        sp(c).edit().putString(K_KIMI, v).apply()

    fun setKimiModel(c: Context, v: String) =
        sp(c).edit().putString(K_KIMI_MODEL, v).apply()
}
