# OkHttp（网络请求：Kimi API）
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Vosk 离线语音识别
-dontwarn org.vosk.**
-keep class org.vosk.** { *; }
-keep interface org.vosk.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# 保留原生方法与枚举
-keepclasseswithmembernames class * { native <methods>; }
