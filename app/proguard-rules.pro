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

# --- Standard R8/ProGuard Rules for Common Libraries ---

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.android.AndroidDispatcherFactory {
    <init>();
}

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-dontwarn okio.**
-dontwarn javax.annotation.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class my.hinoki.booxreader.data.remote.** { *; }
-keep class my.hinoki.booxreader.data.db.** { *; }
# my.hinoki.booxreader.data.model package does not exist in source, but keeping rule is harmless
-keep class my.hinoki.booxreader.data.model.** { *; }
-keep class my.hinoki.booxreader.data.settings.** { *; }
-keep class my.hinoki.booxreader.data.repo.** { *; }
# Fix: The package is data.reader, not reader. Also keep reader for LocatorJsonHelper which has weird package.
-keep class my.hinoki.booxreader.data.reader.** { *; }
-keep class my.hinoki.booxreader.reader.** { *; }
-keep class my.hinoki.booxreader.data.core.** { *; }
# Keep UI classes to ensure Activities/Adapters/ViewModels work correctly
-keep class my.hinoki.booxreader.data.ui.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Readium (Prevent overly aggressive stripping of navigator internals)
-keep class org.readium.** { *; }
-dontwarn org.readium.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Strip Log.* calls in release to reduce noise and size.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
