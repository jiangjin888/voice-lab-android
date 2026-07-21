package com.voicelab.app.util

import android.content.Context
import java.io.IOException
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * 离线语音识别（Vosk）。中文模型放在 app/src/main/assets/model-zh-cn/，
 * 首次启动由 StorageService 解包到内部存储，之后直接加载，全程不联网。
 * 提供流式回调：onPartial（实时字幕）、onFinal（成句结果）、onError（含原因）。
 */
class VoskAsr(private val ctx: Context) {

    interface AsrCallback {
        fun onStatus(msg: String)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(msg: String)
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var loading = false
    private var initialized = false

    fun isInitialized(): Boolean = initialized

    fun init(callback: AsrCallback) {
        if (initialized || loading) return
        loading = true
        callback.onStatus("正在加载离线语音模型…")
        StorageService.unpack(ctx, "model-zh-cn", "model",
            object : StorageService.Callback<Model> {
                override fun onComplete(result: Model?) {
                    model = result
                    initialized = true
                    loading = false
                    callback.onStatus("离线模型就绪")
                }
            },
            object : StorageService.Callback<IOException> {
                override fun onComplete(e: IOException?) {
                    loading = false
                    callback.onError(
                        "离线模型加载失败：${e?.message}\n请把 vosk-model-small-cn-0.22 解压后重命名为 model-zh-cn，" +
                        "放到 app/src/main/assets/model-zh-cn/ 再重新编译。"
                    )
                }
            }
        )
    }

    fun start(callback: AsrCallback) {
        if (!initialized) { init(callback); return }
        val m = model ?: run { init(callback); return }
        if (speechService == null) {
            speechService = SpeechService(Recognizer(m, 16000.0f), 16000.0f)
        }
        callback.onStatus("聆听中…说完点“停止”")
        speechService?.startListening(object : RecognitionListener {
            override fun onPartialResult(hyp: String?) {
                val t = parseField(hyp, "partial")
                if (t.isNotBlank()) callback.onPartial(t)
            }

            override fun onResult(hyp: String?) {
                val t = parseField(hyp, "text")
                if (t.isNotBlank()) callback.onFinal(t)
            }

            override fun onFinalResult(hyp: String?) {
                val t = parseField(hyp, "text")
                if (t.isNotBlank()) callback.onFinal(t)
            }

            override fun onError(e: Exception?) {
                callback.onError("识别错误：${e?.message}")
            }

            override fun onTimeout() { }
        })
    }

    fun stop() {
        speechService?.stop()
    }

    fun shutdown() {
        try { speechService?.stop() } catch (_: Exception) { }
        speechService = null
        try { model?.close() } catch (_: Exception) { }
    }

    private fun parseField(json: String?, field: String): String {
        if (json.isNullOrBlank()) return ""
        return try {
            JSONObject(json).optString(field, "").trim()
        } catch (_: Exception) { "" }
    }
}
