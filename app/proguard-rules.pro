# Kotlin
-keepattributes *Annotation*
-keep class kotlin.Metadata { *; }

# WebView
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# DeepSeek Agent
-keep class com.deepseek.agent.** { *; }
