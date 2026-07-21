# 语音实验记录助手（原生 Android · 离线语音 + Kimi 解析）

本地 **Vosk 离线语音识别**（中文模型，不联网）+ **Kimi(Moonshot) 智能解析** + 后台系统闹钟 + 任务/实验记录 + 筛选 + 导出 的原生安卓 App（Kotlin）。

> 语音识别走本地 Vosk 模型（约 40MB，需放到 assets），完全离线、不依赖任何语音云服务；Kimi 仅负责把识别出的文字解析成结构化任务/实验时间/总结（可选，不填则用本地规则兜底）。

## 功能
- 🎙️ **离线语音识别（Vosk）**：点麦克风后实时显示识别文字，说完点停止即完成记录。完全不联网，无需语音云密钥。
- 🧠 **Kimi 智能解析**：识别出的文字交给 Kimi，自动提取「是否任务 / 任务标题 / 计划时间 / 实验起止 / 总结」，生成结构化记录。Kimi 不可用时自动回退本地规则解析。
- ⏰ **后台系统闹钟**：用 `AlarmManager.setExactAndAllowWhileIdle` 设精确闹钟，息屏/App 在后台也会到点弹通知提醒（通知可一键“完成”）。
- 🔁 **次日跟进**：再次打开 App 时，自动列出已过期的待完成任务，问你“完成了吗”。
- 📋 **记录表格**：每句话 + 自动解析的任务（内容 / 计划时间 / 状态 / 实验起止 / 来源），状态可改、实验起止可填。
- 🔍 **按日期/关键词筛选**：任务与语句均可按“创建日期范围 + 关键词”过滤。
- ⬇️ **导出**：导出为 Excel 可直接打开的 CSV（含 UTF-8 BOM）。
- ⌨️ **文字输入**：也能打字提交，同样走 Kimi 解析建任务。
- 🩺 **诊断面板**：显示每一步（加载模型 → 开始聆听 → 识别成功/失败及原因），一眼定位麦克风/识别问题。

## 环境要求
- Android Studio（最新版，自带 JDK 17）——**可选**；不想装这么大，直接用下面的「GitHub Actions 云端出 APK」，电脑零安装
- 测试机 Android 7.0（API 24）及以上
- **真机**（模拟器多为 x86，Vosk 原生库主要提供 arm 架构；强烈建议用真机）
- 麦克风权限；Kimi 解析需联网（仅解析阶段），离线语音识别本身不需要网络

## 第一步：放入中文语音模型（必须）
1. 下载 `vosk-model-small-cn-0.22`：https://alphacephei.com/vosk/models
2. 解压，把文件夹**重命名为 `model-zh-cn`**
3. 放到 `app/src/main/assets/model-zh-cn/`（即 assets 目录下要有 `model-zh-cn/` 这一层）
4. 重新编译；首次启动会自动解包到内部存储（约几十秒，诊断面板会显示进度）

## 第二步：Kimi Key（可选，用于智能解析）
不填也能用：此时用内置本地规则解析中文时间。想用 Kimi 更聪明地理解口语，就填：
1. 打开 https://platform.moonshot.cn/ 注册
2. 在「API 密钥」页新建一个 Key
3. 默认模型 `moonshot-v1-8k`（可在设置里改）

### 在 App 里填写
首次打开 App → 点右上角 **⚙️ 设置** → 填「Kimi API Key / 模型」→ 保存。
（密钥只存在本机 SharedPreferences，不会上传。）

## 第三步：编译 / 出 APK
1. Android Studio → `Open` → 选择本工程根目录（`settings.gradle.kts` 所在目录）
2. 等待 Gradle 同步完成
3. 真机调试：`Run ▶`（需开启手机“开发者选项 → USB 调试”）
4. 出正式 APK：`Build → Build Bundle(s) / APK(s) → Build APK(s)`，产物在 `app/build/outputs/apk/`

## 免装 Android Studio：云端一键出 APK（GitHub Actions）
你**完全不用安装 Android Studio**。本工程已内置 `.github/workflows/build-apk.yml`：把代码推到 GitHub，GitHub 的服务器会帮你下载 SDK、编译、打包，你只下载成品 APK。自己电脑零安装（SDK 那 ~1GB 下载发生在 GitHub 的机器上，不是你的）。

步骤：
1. 注册 GitHub 账号（免费）：https://github.com
2. 新建一个**空**仓库（不要勾选 README/.gitignore，保持全空），例如 `voicelab-android`
3. 把本工程整个文件夹推上去。最省事用 **GitHub Desktop**（图形界面，安装包很小，不是 Android Studio）：
   - 下载安装：https://desktop.github.com/
   - `File → Add Local Repository` → 选 `voice-lab-android` 文件夹 → `Publish` 到刚建的仓库
   - 模型 `app/src/main/assets/model-zh-cn` 已包含在内会一起上传（约 66MB，首次推送稍慢但没问题）
4. 推送后自动开始编译：打开仓库页面 → **Actions** 标签 → 看到 `Build Debug APK` 在跑
5. 跑完（约 3–6 分钟）→ 点进去 → 右侧 **Artifacts** 下载 `voicelab-debug-apk`（里面是 `app-debug.apk`）
6. 把 `app-debug.apk` 传到手机安装即可（安装未知来源应用需在设置里允许）

> 之后想重新出包：每次 `push` 都会自动重新编译；也可在 Actions 页面点 `Run workflow` 手动触发。
> 若编译报错：把 Actions 日志贴给我，我直接改。最常见只有一种——`vosk-android:0.3.47` 版本号失效，我把 `app/build.gradle.kts` 里的版本换成 Maven Central 最新号即可。

## 第四步：权限
首次使用会请求：
- **麦克风**：录音必需
- **通知**：提醒通知必需（Android 13+）
- **精确闹钟**：Android 12+ 会跳到系统设置页，请允许（用于后台提醒）
- **网络**：仅 Kimi 解析需要联网（普通权限，自动授予）

## 使用流程
1. 点麦克风 → 实时显示识别文字 → 再点停止 → 自动交给 Kimi 解析成任务 → 落库（到点提醒）。
2. 诊断区显示步骤：加载模型 → 开始聆听 → 识别成功/失败（含原因）。
3. 也可直接在「文字输入」里打字提交，同样走 Kimi 解析。
4. 第二天打开 App，会自动列出待确认完成情况的任务。

## 已知限制 / 说明
- **离线模型约 40MB**，会增大 APK 体积。
- **导出格式**：当前导出为 `.csv`（Excel 可直接打开，中文不乱码）。如需真正的 `.xlsx`，后续可加 Apache POI。
- **依赖**：`com.alphacephei:vosk-android:0.3.47`（若 Gradle 同步报“找不到版本”，去 Maven Central 查最新号替换）；Kimi 网络请求用 `com.squareup.okhttp3:okhttp:4.12.0`。
- **后台闹钟**：使用 `setExactAndAllowWhileIdle` + 开机自启重排（`BootReceiver`），手机关机再开机后仍会补排未到期的提醒。

## 目录结构
```
voice-lab-android/
├─ settings.gradle.kts
├─ build.gradle.kts
├─ gradle.properties
└─ app/
   ├─ build.gradle.kts
   ├─ proguard-rules.pro
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/voicelab/app/
      │  ├─ VoiceLabApp.kt
      │  ├─ data/        (Sentence, TaskEntity, AppDao, AppDatabase)
      │  ├─ util/        (Config, VoskAsr, KimiClient, TimeParser, AlarmScheduler, NotificationUtils, ExportUtil)
      │  ├─ receiver/    (AlarmReceiver, BootReceiver)
      │  └─ ui/MainActivity.kt
      └─ res/            (layout, values)
```
