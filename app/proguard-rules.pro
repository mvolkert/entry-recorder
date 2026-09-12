# Linphone SDK proguard rules
-dontwarn org.linphone.**
-keep class org.linphone.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Media3
-keep class androidx.media3.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class io.github.mvolkert.entryrecorder.data.model.** { *; }
