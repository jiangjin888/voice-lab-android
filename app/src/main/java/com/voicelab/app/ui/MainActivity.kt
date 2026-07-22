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
import com.voicelab.app.util.SystemAsr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var systemAsr: SystemAsr
    private var vosk: VoskAsr? = null
    private var listening = false
    private var liveText = ""
    private var handledText: String? = null
    private var engine = "auto"   // auto / system / offline

    private var filterKeyword: String? = null
    private var filterFrom: Long? = null
    private var filterTo: Long? = null

    private var pendingExport = "table"   // table / summary
    private var lastSummary: KimiClient.SummaryResult? = null
    private var followupShowing = false

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.ms-excel")
    ) { uri -> if (uri != null) exportToUri(uri) }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) startRecording()
        else toast("需要麦克风权限才能录音")
    }

    // ---------- 系统识别回调 ----------
    private val sysCb = object : SystemAsr.AsrCallback {
        override fun onStatus(msg: String) { setStatus(msg); diag(msg) }
        override fun onPartial(text: String) {
            liveText = text
            findViewById<TextView>(R.id.partialText).text = text.ifBlank { "聆听中…" }
        }
        override fun onFinal(text: String) {
            liveText = text
            if (handledText != text) { handledText = text; onFinalText(text) }
        }
        override fun onError(msg: String) {
            setStatus("识别失败")
            diag("系统识别出错：$msg")
            toast(msg)
            listening = false
            updateMicBtn()
        }
    }

    // ---------- 离线(Vosk)识别回调 ----------
    private val voskCb = object : VoskAsr.AsrCallback {
        override fun onStatus(msg: String) { setStatus(msg); diag(msg) }
        override fun onPartial(text: String) {
            liveText = text
            findViewById<TextView>(R.id.partialText).text = text.ifBlank { "聆听中…" }
        }
        override fun onFinal(text: String) {
            liveText = text
            if (handledText != text) { handledText = text; onFinalText(text) }
        }
        override fun onError(msg: String) {
            setStatus("识别出错")
            diag("离线识别出错：$msg")
            toast(msg)
            listening = false
            updateMicBtn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        db = VoiceLabApp.instance.database
        systemAsr = SystemAsr(this)
        engine = Config.getAsrEngine(this)
        setupUi()
        checkExactAlarmPermission()
        ensureVosk()
        updateKimiStatusOnStart()
        renderAll()
    }

    override fun onResume() {
        super.onResume()
        renderAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        vosk?.shutdown()
        vosk = null
        systemAsr.shutdown()
    }

    private fun setupUi() {
        findViewById<Button>(R.id.micBtn).setOnClickListener { onMicClick() }
        findViewById<Button>(R.id.sendBtn).setOnClickListener { onSubmitText() }
        findViewById<Button>(R.id.filterBtn).setOnClickListener { openFilter() }
        findViewById<Button>(R.id.exportBtn).setOnClickListener { exportXls() }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.genSummary).setOnClickListener { genSummary() }
        findViewById<Button>(R.id.exportSum).setOnClickListener { exportSummaryXls() }
        findViewById<TextView>(R.id.diagText).setOnClickListener {
            val v = findViewById<ScrollView>(R.id.diagScroll)
            v.visibility = if (v.visibility == View.GONE) View.VISIBLE else View.GONE
        }
    }

    // ---------- 权限 ----------
    private fun onMicClick() {
        if (listening) { stopRecording(); return }
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

    // ---------- 语音引擎选择 ----------
    private fun currentEngine(): String = when (engine) {
        "system" -> "system"
        "offline" -> "offline"
        else -> if (systemAsr.isAvailable()) "system" else "offline"
    }

    private fun ensureVosk() {
        if (vosk == null) vosk = VoskAsr(this)
        if (!vosk!!.isInitialized()) {
            setStatus("离线模型加载中…")
            diag("步骤：加载离线语音模型（首次需解包 ~40MB）")
            vosk!!.init(voskCb)
        }
    }

    private fun startRecording() {
        val eng = currentEngine()
        liveText = ""; handledText = null
        listening = true
        updateMicBtn()
        if (eng == "system") {
            setStatus("系统识别：聆听中…说完点停止")
            diag("步骤：启动系统语音识别（中文，云端，更准确）")
            systemAsr.start(sysCb)
        } else {
            if (vosk == null) vosk = VoskAsr(this)
            if (!vosk!!.isInitialized()) {
                toast("离线模型还在加载，请稍候再点")
                ensureVosk(); listening = false; updateMicBtn(); return
            }
            setStatus("离线识别：聆听中…说完点停止")
            diag("步骤：启动离线 Vosk 识别")
            findViewById<TextView>(R.id.partialText).text = "聆听中…"
            vosk!!.start(voskCb)
        }
    }

    private fun stopRecording() {
        if (!listening) return
        listening = false
        updateMicBtn()
        if (currentEngine() == "system") {
            // 系统识别：最终结果由 onResults 回调提交，这里只停止，避免与回调重复记录
            systemAsr.stop()
            setStatus("识别完成")
        } else {
            vosk?.stop()
            setStatus("识别完成")
            val t = liveText.trim()
            if (handledText == null && t.isNotEmpty()) {
                handledText = t
                onFinalText(t)
            } else if (handledText == null) {
                diag("步骤：未识别到内容（可能没说话）")
                toast("没听清，请重试")
            }
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
            var parses: List<KimiClient.KimiParse> = emptyList()
            try {
                parses = KimiClient.parse(this@MainActivity, text, fmtDate(System.currentTimeMillis()))
            } catch (e: Exception) {
                usedKimi = false
                val r = TimeParser.parse(text)
                parses = listOf(KimiClient.KimiParse(
                    isTask = r.createTask, title = r.content.ifBlank { text },
                    planTime = r.scheduled, planTimeText = r.scheduledText,
                    expStart = r.expStart, expEnd = r.expEnd, summary = null, tags = emptyList()))
                withContext(Dispatchers.Main) { diag("Kimi 解析失败，已回退本地规则：${e.message}") }
            }
            val tasks = parses.filter { it.isTask }
            if (tasks.isEmpty()) {
                withContext(Dispatchers.Main) { toast("已记录${if (!usedKimi) "（本地解析，Kimi 不可用）" else ""}") }
            } else {
                var created = 0; var alarm = 0; var follow = 0
                for (k in tasks) {
                    val task = TaskEntity(0, k.title, k.planTime, k.planTimeText, "todo",
                        k.expStart, k.expEnd, source, System.currentTimeMillis())
                    val id = db.taskDao().insertTask(task)
                    created++
                    if (k.planTime != null) {
                        AlarmScheduler.schedule(this@MainActivity, task.copy(id = id)); alarm++
                        AlarmScheduler.scheduleFollowup(this@MainActivity, task.copy(id = id)); follow++
                    }
                }
                withContext(Dispatchers.Main) {
                    toast("已智能编排 $created 条${if (alarm > 0) "（${alarm} 条到点提醒+次日跟进）" else ""}")
                }
            }
            withContext(Dispatchers.Main) { renderAll() }
        }
    }

    // ---------- Kimi 连接状态 ----------
    private fun updateKimiStatusOnStart() {
        if (Config.getKimiKey(this).isBlank()) {
            setKimiStatus("idle", "Kimi: 未配置 Key")
        } else {
            setKimiStatus("load", "Kimi: 连接中…")
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = KimiClient.testConnection(this@MainActivity)
                withContext(Dispatchers.Main) {
                    setKimiStatus(if (ok) "ok" else "err", if (ok) "Kimi: 已连接 ✓" else "Kimi: 连接失败")
                }
            }
        }
    }

    private fun setKimiStatus(state: String, text: String) {
        val dot = findViewById<View>(R.id.kimiDot)
        val color = when (state) {
            "ok" -> R.color.green; "load" -> R.color.amber; "err" -> R.color.red; else -> R.color.muted
        }
        dot.setBackgroundResource(color)
        findViewById<TextView>(R.id.kimiStatus).text = text
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
            val startToday = startOfToday()
            val fu = tasks.filter {
                it.scheduledTime != null && it.scheduledTime < startToday && it.status == "todo" && !Config.isFollowupShown(this@MainActivity, it.id)
            }
            withContext(Dispatchers.Main) {
                renderFollowup(due)
                renderTasks(fTasks)
                renderTable(fTasks)
                renderSentences(fSent)
                if (!followupShowing && fu.isNotEmpty()) showFollowupDialog(fu)
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
        val empty = findViewById<TextView>(R.id.taskListEmpty)
        container.removeAllViews()
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val values = resources.getStringArray(R.array.status_values)
        list.forEach { task ->
            val v = layoutInflater.inflate(R.layout.item_task, container, false)
            v.findViewById<TextView>(R.id.itemContent).text = task.content
            v.findViewById<TextView>(R.id.itemSource).text = if (task.source == "voice") "语音" else "文字"
            v.findViewById<TextView>(R.id.itemScheduled).text = task.scheduledText ?: "未设置"
            v.findViewById<EditText>(R.id.itemExpStart).setText(task.expStart ?: "")
            v.findViewById<EditText>(R.id.itemExpEnd).setText(task.expEnd ?: "")
            v.findViewById<TextView>(R.id.itemTags).text = "无"

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

    // 实验记录表（Excel 风格表格）：记录时间 / 做了什么 / 是否完成 / 时间段
    private fun renderTable(list: List<TaskEntity>) {
        val container = findViewById<LinearLayout>(R.id.taskTable)
        val empty = findViewById<TextView>(R.id.taskEmpty)
        container.removeAllViews()
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        if (list.isEmpty()) return
        container.addView(makeRow(true, "记录时间", "做了什么实验", "是否完成", "实验时间段"))
        list.forEach { t ->
            val time = if (t.expStart != null || t.expEnd != null) "${t.expStart ?: "—"} - ${t.expEnd ?: "—"}" else "—"
            container.addView(makeRow(false, fmtTime(t.created), t.content, statusText(t.status), time))
        }
    }

    private fun makeRow(header: Boolean, vararg cells: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(6, 8, 6, 8)
            setBackgroundColor(if (header) 0xFFDCE6FFL.toInt() else 0)
        }
        val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        cells.forEach { s ->
            row.addView(TextView(this).apply {
                text = s; layoutParams = p; textSize = 12.5f
                setPadding(4, 2, 4, 2)
                if (header) setTypeface(null, android.graphics.Typeface.BOLD)
            })
        }
        return row
    }

    private fun renderSentences(list: List<Sentence>) {
        val container = findViewById<LinearLayout>(R.id.sentList)
        val empty = findViewById<TextView>(R.id.sentEmpty)
        container.removeAllViews()
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        list.forEach { s ->
            val tv = TextView(this).apply {
                text = "· ${fmtTime(s.timestamp)} [${if (s.source == "voice") "语音" else "文字"}] ${s.text}"
                textSize = 13f; setPadding(0, 4, 0, 4)
            }
            container.addView(tv)
        }
    }

    private fun statusText(s: String): String = when (s) {
        "done" -> "已完成"; "fail" -> "未完成"; else -> "未完成"
    }

    private fun setStatusDb(id: Long, status: String) {
        lifecycleScope.launch(Dispatchers.IO) { db.taskDao().updateStatus(id, status) }
        renderAll()
    }

    // ---------- 次日跟进弹窗 ----------
    private fun showFollowupDialog(due: List<TaskEntity>) {
        followupShowing = true
        val ctx = this
        var d: AlertDialog? = null
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 6, 0, 6) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
        due.forEach { t ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 8) }
            row.addView(TextView(this).apply { text = t.content; textSize = 14f })
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val ok = Button(this).apply { text = "已完成"; setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) { db.taskDao().updateStatus(t.id, "done") }
                Config.markFollowupShown(ctx, t.id)
                toast("已记录：${t.content} 已完成")
                followupShowing = false; d?.dismiss()
            } }
            val no = Button(this).apply { text = "未完成"; setOnClickListener {
                lifecycleScope.launch(Dispatchers.IO) { db.taskDao().updateStatus(t.id, "fail") }
                Config.markFollowupShown(ctx, t.id)
                toast("已记录：${t.content} 未完成")
                followupShowing = false; d?.dismiss()
            } }
            btns.addView(ok, lp); btns.addView(no, lp)
            row.addView(btns)
            root.addView(row)
        }
        d = AlertDialog.Builder(this).setTitle("📋 次日跟进：昨日实验完成了吗？")
            .setView(root)
            .setPositiveButton("稍后") { _, _ -> followupShowing = false; renderAll() }
            .setOnCancelListener { followupShowing = false }
            .show()
    }

    // ---------- 今日总结（Kimi 自定义指令）----------
    private fun genSummary() {
        val instr = findViewById<EditText>(R.id.sumInstruct).text.toString().trim()
        val box = findViewById<LinearLayout>(R.id.summaryBox)
        val exportBtn = findViewById<Button>(R.id.exportSum)
        if (Config.getKimiKey(this).isBlank()) { toast("请先在设置里填写 Kimi Key"); openSettings(); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val tasks = db.taskDao().getAllTasks()
            val sentences = db.taskDao().getAllSentences()
            if (tasks.isEmpty() && sentences.isEmpty()) {
                withContext(Dispatchers.Main) { toast("还没有记录可总结") }
                return@launch
            }
            val recText = buildString {
                tasks.forEach { t ->
                    append("- [任务] ${t.content} | 状态:${statusText(t.status)} | 时间段:${t.expStart ?: "?"}-${t.expEnd ?: "?"}\n")
                }
                sentences.forEach { s ->
                    append("- [语句][${if (s.source == "voice") "语音" else "文字"}] ${s.text}\n")
                }
            }
            withContext(Dispatchers.Main) { box.removeAllViews(); box.addView(TextView(this@MainActivity).apply { text = "Kimi 生成中…"; textSize = 13f; setPadding(0, 4, 0, 4) }) }
            try {
                val res = KimiClient.summarize(this@MainActivity, recText, instr, fmtDate(System.currentTimeMillis()))
                lastSummary = res
                withContext(Dispatchers.Main) {
                    box.removeAllViews()
                    box.addView(TextView(this@MainActivity).apply { text = res.title; textSize = 15f; setPadding(0, 4, 0, 8) })
                    box.addView(makeSummaryTable(res.columns, res.rows))
                    exportBtn.visibility = View.VISIBLE
                    toast("✓ Kimi 总结生成成功")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    box.removeAllViews()
                    box.addView(TextView(this@MainActivity).apply { text = "总结失败：${e.message}"; textSize = 13f; setPadding(0, 4, 0, 4) })
                }
            }
        }
    }

    private fun makeSummaryTable(cols: List<String>, rows: List<List<String>>): LinearLayout {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 6, 0, 6) }
        container.addView(makeRow(true, *cols.toTypedArray()))
        rows.forEach { container.addView(makeRow(false, *it.toTypedArray())) }
        return container
    }

    // ---------- 导出 Excel ----------
    private fun exportXls() {
        pendingExport = "table"
        exportLauncher.launch("实验记录表_${fmtDate(System.currentTimeMillis())}.xls")
    }

    private fun exportSummaryXls() {
        if (lastSummary == null) { toast("请先生成总结"); return }
        pendingExport = "summary"
        exportLauncher.launch("今日总结_${fmtDate(System.currentTimeMillis())}.xls")
    }

    private fun exportToUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val tasks = db.taskDao().getAllTasks()
            val content = if (pendingExport == "summary" && lastSummary != null)
                ExportUtil.buildXlsFromTable(lastSummary!!.title, lastSummary!!.columns, lastSummary!!.rows)
            else
                ExportUtil.buildXls(tasks)
            contentResolver.openOutputStream(uri)?.use { os -> os.write(content.toByteArray(Charsets.UTF_8)) }
            withContext(Dispatchers.Main) { toast("已导出 Excel 到所选位置") }
        }
    }

    // ---------- 筛选 ----------
    private fun openFilter() {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 8, 0, 8)
        val kw = EditText(this).apply { hint = "关键词（内容包含）"; setText(filterKeyword ?: "") }
        val fromBtn = Button(this).apply { text = if (filterFrom != null) fmtDate(filterFrom!!) else "开始日期（不选=不限）" }
        val toBtn = Button(this).apply { text = if (filterTo != null) fmtDate(filterTo!!) else "结束日期（不选=不限）" }
        var from: Long? = filterFrom; var to: Long? = filterTo
        fromBtn.setOnClickListener { pickDate(from) { d -> from = d; fromBtn.text = fmtDate(d) } }
        toBtn.setOnClickListener { pickDate(to) { d -> to = d; toBtn.text = fmtDate(d) } }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20); addView(kw, lp); addView(fromBtn, lp); addView(toBtn, lp) }
        AlertDialog.Builder(this).setTitle("筛选（日期按创建时间）")
            .setView(layout)
            .setPositiveButton("确定") { _, _ ->
                filterKeyword = kw.text.toString().trim().takeIf { it.isNotEmpty() }
                filterFrom = from; filterTo = to; renderAll()
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

    private fun filterTasks(tasks: List<TaskEntity>): List<TaskEntity> =
        tasks.filter { matchDate(it.created) && matchKw(it.content) }

    private fun filterSentences(sent: List<Sentence>): List<Sentence> =
        sent.filter { matchDate(it.timestamp) && matchKw(it.text) }

    private fun matchKw(s: String): Boolean = filterKeyword == null || s.contains(filterKeyword!!, ignoreCase = true)

    private fun matchDate(t: Long): Boolean {
        if (filterFrom != null && t < filterFrom!!) return false
        if (filterTo != null) {
            val end = Calendar.getInstance().apply { timeInMillis = filterTo!!; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59) }
            if (t > end.timeInMillis) return false
        }
        return true
    }

    // ---------- 设置（Kimi Key / 模型 / 测试连接 / 识别引擎）----------
    private fun openSettings() {
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 8, 0, 8) }
        val kk = EditText(this).apply { hint = "Kimi API Key（不填则用本地规则解析）"; setText(Config.getKimiKey(this@MainActivity)) }
        val km = EditText(this).apply { hint = "Kimi 模型（默认 moonshot-v1-8k）"; setText(Config.getKimiModel(this@MainActivity)) }
        val engine = Config.getAsrEngine(this)
        val rg = RadioGroup(this)
        val opts = listOf("auto" to "自动（系统优先，失败回退离线）", "system" to "仅系统识别（更准，需联网）", "offline" to "仅离线 Vosk（不联网）")
        opts.forEach { (v, label) ->
            val rb = RadioButton(this).apply { text = label; isChecked = (v == engine) }
            rb.tag = v
            rg.addView(rb, lp)
        }
        val status = TextView(this).apply { text = "当前：${findViewById<TextView>(R.id.kimiStatus).text}"; textSize = 13f; setPadding(0, 4, 0, 4) }
        val testBtn = Button(this).apply { text = "测试连接" }
        testBtn.setOnClickListener {
            val key = kk.text.toString().trim(); val model = km.text.toString().trim()
            Config.setKimiKey(this, key); Config.setKimiModel(this, model)
            if (key.isBlank()) { toast("请先填写 Key"); return@setOnClickListener }
            status.text = "连接中…"
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = KimiClient.testConnection(this@MainActivity)
                withContext(Dispatchers.Main) {
                    status.text = if (ok) "✓ 连接成功" else "✗ 连接失败"
                    setKimiStatus(if (ok) "ok" else "err", if (ok) "Kimi: 已连接 ✓" else "Kimi: 连接失败")
                }
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20)
            addView(TextView(this@MainActivity).apply { text = "Kimi API Key" }); addView(kk, lp)
            addView(TextView(this@MainActivity).apply { text = "模型" }); addView(km, lp)
            addView(testBtn, lp)
            addView(status, lp)
            addView(TextView(this@MainActivity).apply { text = "语音识别引擎" }); addView(rg, lp)
        }
        AlertDialog.Builder(this).setTitle("设置（密钥仅存本机）")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                Config.setKimiKey(this, kk.text.toString().trim())
                Config.setKimiModel(this, km.text.toString().trim())
                val sel = rg.findViewById<RadioButton>(rg.checkedRadioButtonId)
                val ev = sel?.tag as? String ?: "auto"
                Config.setAsrEngine(this, ev)
                this@MainActivity.engine = ev
                toast("已保存")
                if (kk.text.toString().trim().isNotBlank()) updateKimiStatusOnStart()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 小工具 ----------
    private fun setStatus(s: String) { findViewById<TextView>(R.id.statusText).text = s }

    private fun diag(s: String) {
        val tv = findViewById<TextView>(R.id.diagText)
        val cur = tv.text.toString()
        val lines = if (cur.isEmpty()) mutableListOf() else cur.split("\n").toMutableList()
        lines.add(0, s)
        while (lines.size > 12) lines.removeAt(lines.size - 1)   // 只保留最近 12 行，避免占屏
        tv.text = lines.joinToString("\n")
        findViewById<ScrollView>(R.id.diagScroll).visibility = View.VISIBLE
    }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun startOfToday(): Long {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return c.timeInMillis
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
