package com.voicelab.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.DatePickerDialog
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import com.voicelab.app.R
import com.voicelab.app.VoiceLabApp
import com.voicelab.app.data.AppDatabase
import com.voicelab.app.data.Sentence
import com.voicelab.app.data.TaskEntity
import com.voicelab.app.util.AlarmScheduler
import com.voicelab.app.util.Config
import com.voicelab.app.util.ExportUtil
import com.voicelab.app.util.KimiClient
import com.voicelab.app.util.TimeParser
import com.voicelab.app.util.VoskAsr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private var vosk: VoskAsr? = null
    private var listening = false
    private var liveText = ""   // 当前这次录音识别到的文字（实时更新）
    private var handledText: String? = null  // 已落库的最终文本，防止重复记录

    private var filterKeyword: String? = null
    private var filterFrom: Long? = null
    private var filterTo: Long? = null

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) exportToUri(uri) }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) startRecording()
    }

    // 离线识别回调：实时字幕 + 成句结果 + 错误
    private val asrCb = object : VoskAsr.AsrCallback {
        override fun onStatus(msg: String) {
            setStatus(msg)
            diag("步骤：$msg")
        }
        override fun onPartial(text: String) {
            liveText = text
            findViewById<TextView>(R.id.partialText).text = text.ifBlank { "聆听中…" }
        }
        override fun onFinal(text: String) {
            liveText = text
            if (handledText != text) {
                handledText = text
                onFinalText(text)
            }
        }
        override fun onError(msg: String) {
            setStatus("识别出错")
            diag("识别出错：$msg")
            toast(msg)
            listening = false
            updateMicBtn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = VoiceLabApp.instance.database
        setupUi()
        checkExactAlarmPermission()
        ensureVosk()      // 后台预加载离线模型
        renderAll()
    }

    override fun onResume() {
        super.onResume()
        renderAll()
    }

    override fun onPause() {
        super.onPause()
        if (listening) stopAndTranscribe()
    }

    override fun onDestroy() {
        super.onDestroy()
        vosk?.shutdown()
        vosk = null
    }

    private fun setupUi() {
        findViewById<Button>(R.id.micBtn).setOnClickListener { onMicClick() }
        findViewById<Button>(R.id.sendBtn).setOnClickListener { onSubmitText() }
        findViewById<Button>(R.id.filterBtn).setOnClickListener { openFilter() }
        findViewById<Button>(R.id.exportBtn).setOnClickListener { exportCsv() }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener { openSettings() }
    }

    // ---------- 权限 ----------
    private fun onMicClick() {
        if (listening) { stopAndTranscribe(); return }
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray()) else startRecording()
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (!am.canScheduleExactAlarms()) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) { }
                toast("请允许本应用设置精确闹钟（用于后台提醒）")
            }
        }
    }

    // ---------- 离线语音识别（Vosk）----------
    private fun ensureVosk() {
        if (vosk == null) vosk = VoskAsr(this)
        if (!vosk!!.isInitialized()) {
            setStatus("离线模型加载中…")
            diag("步骤：加载离线语音模型（首次需解包 ~40MB，请稍候）")
            vosk!!.init(asrCb)
        } else {
            setStatus("离线模型就绪")
        }
    }

    private fun startRecording() {
        if (vosk == null) vosk = VoskAsr(this)
        if (!vosk!!.isInitialized()) {
            toast("离线模型还在加载，请稍候再点（首次解包约需几十秒）")
            ensureVosk()
            return
        }
        liveText = ""
        handledText = null
        listening = true
        updateMicBtn()
        setStatus("聆听中…说完点停止")
        diag("步骤：开始聆听（离线 Vosk 识别中）")
        findViewById<TextView>(R.id.partialText).text = "聆听中…"
        vosk!!.start(asrCb)
    }

    private fun stopAndTranscribe() {
        if (!listening) return
        listening = false
        vosk?.stop()
        updateMicBtn()
        setStatus("识别完成")
        val t = liveText.trim()
        if (handledText == null && t.isNotEmpty()) {
            diag("步骤：识别成功 → $t")
            onFinalText(t)
        } else {
            diag("步骤：未识别到内容（可能没说话或环境太吵）")
            toast("没听清，请重试")
        }
    }

    private fun updateMicBtn() {
        findViewById<Button>(R.id.micBtn).text = if (listening) "⏹ 停止并识别" else "🎙️ 开始录音"
    }

    private fun onFinalText(t: String) {
        val ft = findViewById<TextView>(R.id.finalText)
        ft.visibility = View.VISIBLE
        ft.text = t
        handleUtterance(t, "voice")
    }

    // ---------- 记录与任务解析（Kimi 为主，本地规则兜底）----------
    private fun onSubmitText() {
        val ta = findViewById<EditText>(R.id.textInput)
        val v = ta.text.toString().trim()
        if (v.isEmpty()) { toast("请输入内容"); return }
        handleUtterance(v, "text")
        ta.setText("")
    }

    private fun handleUtterance(text: String, source: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.taskDao().insertSentence(Sentence(0, text, source, System.currentTimeMillis()))

            var usedKimi = true
            var createTask: Boolean
            var content: String
            var scheduled: Long?
            var scheduledText: String?
            var expStart: String?
            var expEnd: String?

            try {
                val k = KimiClient.parse(this@MainActivity, text, fmtDate(System.currentTimeMillis()))
                usedKimi = true
                createTask = k.isTask
                content = k.title.ifBlank { text }
                scheduled = k.planTime
                scheduledText = k.planTimeText
                expStart = k.expStart
                expEnd = k.expEnd
            } catch (e: Exception) {
                // Kimi 不可用（无 Key / 网络错误）→ 本地规则兜底
                usedKimi = false
                val r = TimeParser.parse(text)
                createTask = r.createTask
                content = r.content.ifBlank { text }
                scheduled = r.scheduled
                scheduledText = r.scheduledText
                expStart = r.expStart
                expEnd = r.expEnd
                withContext(Dispatchers.Main) {
                    diag("Kimi 解析失败，已回退本地规则：${e.message}")
                }
            }

            if (createTask) {
                val task = TaskEntity(0, content, scheduled, scheduledText, "todo", expStart, expEnd, source, System.currentTimeMillis())
                val id = db.taskDao().insertTask(task)
                if (scheduled != null) AlarmScheduler.schedule(this@MainActivity, task.copy(id = id))
                withContext(Dispatchers.Main) {
                    toast("已创建任务${if (scheduled != null) "（已设提醒 ${scheduledText}）" else ""}：$content")
                }
            } else {
                withContext(Dispatchers.Main) {
                    toast("已记录${if (!usedKimi) "（本地解析，Kimi 不可用）" else ""}")
                }
            }
            withContext(Dispatchers.Main) { renderAll() }
        }
    }

    // ---------- 渲染 ----------
    private fun renderAll() {
        lifecycleScope.launch(Dispatchers.IO) {
            val tasks = db.taskDao().getAllTasks()
            val sentences = db.taskDao().getAllSentences()
            val now = System.currentTimeMillis()
            val due = tasks.filter { it.scheduledTime != null && it.scheduledTime < now && it.status == "todo" }
            val fTasks = filterTasks(tasks)
            val fSent = filterSentences(sentences)
            withContext(Dispatchers.Main) {
                renderFollowup(due)
                renderTasks(fTasks)
                renderSentences(fSent)
            }
        }
    }

    private fun renderFollowup(due: List<TaskEntity>) {
        val card = findViewById<LinearLayout>(R.id.fuCard)
        val list = findViewById<LinearLayout>(R.id.fuList)
        if (due.isEmpty()) { card.visibility = View.GONE; return }
        card.visibility = View.VISIBLE
        list.removeAllViews()
        due.forEach { t ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 8, 0, 8) }
            val txt = TextView(this).apply {
                text = t.content + (if (t.scheduledText != null) "\n计划：${t.scheduledText}" else "")
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 14f
            }
            val ok = Button(this).apply { text = "已完成"; setOnClickListener { setStatusDb(t.id, "done") } }
            val no = Button(this).apply { text = "未完成"; setOnClickListener { setStatusDb(t.id, "fail") } }
            row.addView(txt); row.addView(ok); row.addView(no)
            list.addView(row)
        }
    }

    private fun renderTasks(list: List<TaskEntity>) {
        val container = findViewById<LinearLayout>(R.id.taskList)
        val empty = findViewById<TextView>(R.id.taskEmpty)
        container.removeAllViews()
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val values = resources.getStringArray(R.array.status_values)
        list.forEach { task ->
            val v = layoutInflater.inflate(R.layout.item_task, container, false)
            v.findViewById<TextView>(R.id.itemContent).text = task.content
            v.findViewById<TextView>(R.id.itemSource).text = if (task.source == "voice") "语音" else "文字"
            v.findViewById<TextView>(R.id.itemScheduled).text =
                if (task.scheduledText != null) "计划：${task.scheduledText}" else "无计划时间"
            v.findViewById<EditText>(R.id.itemExpStart).setText(task.expStart ?: "")
            v.findViewById<EditText>(R.id.itemExpEnd).setText(task.expEnd ?: "")

            val spinner = v.findViewById<Spinner>(R.id.itemStatus)
            spinner.setSelection(values.indexOf(task.status).coerceAtLeast(0))
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>, view: View?, pos: Int, id: Long) {
                    val nv = values[pos]
                    if (nv != task.status) {
                        task.status = nv
                        lifecycleScope.launch(Dispatchers.IO) { db.taskDao().updateStatus(task.id, nv) }
                        renderAll()
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>) { }
            }

            val startEt = v.findViewById<EditText>(R.id.itemExpStart)
            val endEt = v.findViewById<EditText>(R.id.itemExpEnd)
            val saveExp = View.OnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val s = startEt.text.toString().trim().ifEmpty { null }
                    val e = endEt.text.toString().trim().ifEmpty { null }
                    lifecycleScope.launch(Dispatchers.IO) { db.taskDao().updateExp(task.id, s, e) }
                }
            }
            startEt.onFocusChangeListener = saveExp
            endEt.onFocusChangeListener = saveExp

            v.findViewById<Button>(R.id.itemDelete).setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    db.taskDao().deleteTask(task)
                    if (task.scheduledTime != null) AlarmScheduler.cancel(this@MainActivity, task.id)
                }
                renderAll()
            }

            container.addView(v)
        }
    }

    private fun renderSentences(list: List<Sentence>) {
        val container = findViewById<LinearLayout>(R.id.sentList)
        val empty = findViewById<TextView>(R.id.sentEmpty)
        container.removeAllViews()
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        list.forEach { s ->
            val tv = TextView(this).apply {
                text = "· ${fmtTime(s.timestamp)} [${if (s.source == "voice") "语音" else "文字"}] ${s.text}"
                textSize = 13f
                setPadding(0, 4, 0, 4)
            }
            container.addView(tv)
        }
    }

    private fun setStatusDb(id: Long, status: String) {
        lifecycleScope.launch(Dispatchers.IO) { db.taskDao().updateStatus(id, status) }
        renderAll()
    }

    // ---------- 筛选 ----------
    private fun openFilter() {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 8, 0, 8)
        val kw = EditText(this).apply { hint = "关键词（内容包含）"; setText(filterKeyword ?: "") }
        val fromBtn = Button(this).apply { text = if (filterFrom != null) fmtDate(filterFrom!!) else "开始日期（不选=不限）" }
        val toBtn = Button(this).apply { text = if (filterTo != null) fmtDate(filterTo!!) else "结束日期（不选=不限）" }
        var from: Long? = filterFrom
        var to: Long? = filterTo
        fromBtn.setOnClickListener { pickDate(from) { d -> from = d; fromBtn.text = fmtDate(d) } }
        toBtn.setOnClickListener { pickDate(to) { d -> to = d; toBtn.text = fmtDate(d) } }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
            addView(kw, lp); addView(fromBtn, lp); addView(toBtn, lp)
        }
        AlertDialog.Builder(this).setTitle("筛选（日期按创建时间）")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                filterKeyword = kw.text.toString().trim().takeIf { it.isNotEmpty() }
                filterFrom = from; filterTo = to
                renderAll()
            }
            .setNeutralButton("清除") { _, _ -> filterKeyword = null; filterFrom = null; filterTo = null; renderAll() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickDate(initial: Long?, cb: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { if (initial != null) timeInMillis = initial }
        DatePickerDialog(this, { _, y, m, d ->
            val c = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
            cb(c.timeInMillis)
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DATE)).show()
    }

    private fun filterTasks(tasks: List<TaskEntity>): List<TaskEntity> {
        return tasks.filter { matchDate(it.created) && matchKw(it.content) }
    }

    private fun filterSentences(sent: List<Sentence>): List<Sentence> {
        return sent.filter { matchDate(it.timestamp) && matchKw(it.text) }
    }

    private fun matchKw(s: String): Boolean = filterKeyword == null || s.contains(filterKeyword!!, ignoreCase = true)

    private fun matchDate(t: Long): Boolean {
        if (filterFrom != null && t < filterFrom!!) return false
        if (filterTo != null) {
            val end = Calendar.getInstance().apply { timeInMillis = filterTo!!; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59) }
            if (t > end.timeInMillis) return false
        }
        return true
    }

    // ---------- 设置（仅 Kimi Key）----------
    private fun openSettings() {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 8, 0, 8) }
        val kk = EditText(this).apply { hint = "Kimi API Key（不填则用本地规则解析）"; setText(Config.getKimiKey(this@MainActivity)) }
        val km = EditText(this).apply { hint = "Kimi 模型（默认 moonshot-v1-8k）"; setText(Config.getKimiModel(this@MainActivity)) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
            addView(kk, lp); addView(km, lp)
        }
        AlertDialog.Builder(this).setTitle("API 设置（密钥仅存本机）")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                Config.setKimiKey(this@MainActivity, kk.text.toString().trim())
                Config.setKimiModel(this@MainActivity, km.text.toString().trim())
                toast("已保存（Kimi 可选；离线语音识别无需密钥）")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 导出 ----------
    private fun exportCsv() {
        exportLauncher.launch("语音实验记录_${fmtDate(System.currentTimeMillis())}.csv")
    }

    private fun exportToUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val tasks = db.taskDao().getAllTasks()
            val sentences = db.taskDao().getAllSentences()
            val csv = ExportUtil.buildCsv(tasks, sentences)
            contentResolver.openOutputStream(uri)?.use { os -> os.write(csv.toByteArray(Charsets.UTF_8)) }
            withContext(Dispatchers.Main) { toast("已导出到所选位置") }
        }
    }

    // ---------- 小工具 ----------
    private fun setStatus(s: String) { findViewById<TextView>(R.id.statusText).text = s }

    private fun diag(s: String) {
        val tv = findViewById<TextView>(R.id.diagText)
        val cur = tv.text.toString()
        tv.text = if (cur.isEmpty()) s else "$s\n$cur"
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    private fun fmtDate(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return "%d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DATE))
    }

    private fun fmtTime(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return "%d-%02d-%02d %02d:%02d".format(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DATE),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)
        )
    }
}
