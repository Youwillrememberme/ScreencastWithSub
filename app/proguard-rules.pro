# --- Cling / UPnP (uses reflection on model classes) ---
-keep class org.fourthline.cling.model.** { *; }
-keep class org.fourthline.cling.support.** { *; }
-keep class org.seamless.** { *; }
-dontwarn org.seamless.**
-dontwarn org.fourthline.**

# --- FFmpeg kit ---
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.**

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { *; }

# --- Kotlin metadata ---
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
