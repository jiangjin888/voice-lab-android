package com.voicelab.app.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 系统语音识别（android.speech.SpeechRecognizer）。
 * 云端识别、中文准确度高，是 Vosk 离线识别之外“更准”的方案。
 * 需要 RECORD_AUDIO 权限与联网；若设备未提供语音识别服务（如部分无 GMS 的国内机型），
 * 会自动回退到 Vosk 离线识别（见 MainActivity 的引擎选择逻辑）。
 *
 * 注意：SpeechRecognizer 必须在主线程创建。
 */
class SystemAsr(private val ctx: Context) {

    interface AsrCallback {
        fun onStatus(msg: String)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(msg: String)
    }

    private var recognizer: SpeechRecognizer? = null

    /** 设备是否提供系统语音识别服务 */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    fun start(callback: AsrCallback) {
        if (recognizer == null) {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
            } catch (e: Exception) {
                callback.onError("系统语音识别不可用：${e.message}")
                return
            }
        }
        val rec = recognizer ?: run {
            callback.onError("系统语音识别不可用（设备未提供语音识别服务，请改用离线识别）")
            return
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                callback.onStatus("聆听中…说完点“停止”")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                callback.onError(sysErr(error))
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val t = matches?.firstOrNull()?.trim() ?: ""
                if (t.isNotEmpty()) callback.onFinal(t) else callback.onError("未识别到内容，请再说一次")
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val t = matches?.firstOrNull()?.trim() ?: ""
                if (t.isNotEmpty()) callback.onPartial(t)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        callback.onStatus("正在启动系统语音识别…")
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            callback.onError("系统识别启动失败：${e.message}（可改用离线识别）")
        }
    }

    fun stop() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
    }

    fun shutdown() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    private fun sysErr(code: Int): String {
        val map = mapOf(
            SpeechRecognizer.ERROR_AUDIO to "录音错误（麦克风被占用或设备异常）",
            SpeechRecognizer.ERROR_CLIENT to "客户端错误（可能未授权麦克风，或系统识别服务不可用）",
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to "麦克风权限不足，请在设置里允许",
            SpeechRecognizer.ERROR_NETWORK to "网络异常（系统识别需联网，请检查网络）",
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT to "网络超时",
            SpeechRecognizer.ERROR_NO_MATCH to "没听清，请靠近麦克风再说一次",
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY to "识别器忙，请稍后重试",
            SpeechRecognizer.ERROR_SERVER to "识别服务错误，请稍后重试",
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT to "没听到声音，请靠近麦克风重试"
        )
        return map[code] ?: "系统识别错误（code=$code）"
    }
}
