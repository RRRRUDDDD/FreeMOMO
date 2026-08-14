# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Xposed 入口（xposed_init 按类名加载，不能被重命名或移除）
-keep class com.rud.freemomo.MomoHookEntry {
    *;
}

# Hook 类（在入口中直接实例化，保留所有成员）
-keep class com.rud.freemomo.hook.** { *; }

# 缓存工具类
-keep class com.rud.freemomo.util.** { *; }
