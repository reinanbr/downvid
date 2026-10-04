# JNI entry points implemented in Go (go/dvcore/jni_android.go): the native
# method names are part of the ABI and must not be renamed.
-keepclasseswithmembernames class com.downvid.downvid.DvCore {
    native <methods>;
}

# Methods called from JavaScript in WebViews (InstagramQuery).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# youtubedl-android (Python/yt-dlp/ffmpeg wrapper) and its dependencies use
# reflection (Jackson) and resource lookups.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keep class org.apache.commons.** { *; }
-dontwarn org.apache.commons.**
-dontwarn java.beans.**
