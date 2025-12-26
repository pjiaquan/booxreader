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

# Preserve the line number information for debugging stack traces
# This helps debug ProGuard/R8 issues in release builds
-keepattributes SourceFile,LineNumberTable

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

# Gson - Comprehensive rules to prevent serialization issues
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep generic signature of TypeToken and its subclasses
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Keep all fields with @SerializedName annotation
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# Keep all Gson-serializable classes and their fields
# This prevents field names from being obfuscated, which breaks JSON serialization
-keep class * implements com.google.gson.JsonSerializer { *; }
-keep class * implements com.google.gson.JsonDeserializer { *; }

# Prevent R8 from removing or obfuscating fields in data classes
# Without this, even with LOWER_CASE_WITH_UNDERSCORES policy, fields get renamed
-keepclassmembers class my.hinoki.booxreader.data.repo.Supabase* { *; }
-keepclassmembers class my.hinoki.booxreader.data.remote.Supabase* { *; }

# Protect all data classes, repositories, ViewModels, and Utilities
# This covers: data.auth, data.db, data.remote, data.repo, data.settings, data.util, data.prefs, data.core
-keep class my.hinoki.booxreader.data.** { *; }

# Protect UI classes (Activities, Fragments, Adapters)
# Some files might have package my.hinoki.booxreader.ui despite being in data/ui directory
-keep class my.hinoki.booxreader.ui.** { *; }

# Protect legacy/mismatched reader package (e.g. LocatorJsonHelper)
-keep class my.hinoki.booxreader.reader.** { *; }
-keep class my.hinoki.booxreader.core.** { *; }

# Keep specific Android components if needed (usually covered by default rules, but good for safety)
-keep class * extends android.app.Activity
-keep class * extends androidx.fragment.app.Fragment
-keep class * extends android.view.View
-keep class * extends androidx.lifecycle.ViewModel

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
