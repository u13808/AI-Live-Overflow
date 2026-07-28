# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /sdk/tools/proguard/proguard-android-optimize.txt

# Keep WebView JS interface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Service
-keep class com.example.deskpet.service.** { *; }

# Keep Supabase HTTP utils
-dontwarn java.net.**
-dontwarn javax.**
