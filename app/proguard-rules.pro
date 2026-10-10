# Linphone SDK proguard rules
-dontwarn org.linphone.**
-keep class org.linphone.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Media3
-keep class androidx.media3.** { *; }

# Gson reflects over the field names of the classes the app actually serializes; the old rule kept only
# `data.model.**`, which is the enums those classes carry. These are the JSON models: the server DTOs and
# payloads, the settings backup and the entity types it embeds.
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class io.github.mvolkert.entryrecorder.data.server.** { *; }
-keepclassmembers class io.github.mvolkert.entryrecorder.data.backup.AppBackup { *; }
-keepclassmembers class io.github.mvolkert.entryrecorder.data.local.entity.** { *; }
-keepclassmembers class io.github.mvolkert.entryrecorder.data.model.** { *; }
# The same types also have to survive as classes, but their names are free to change: Gson is handed the
# Class object by the call site, so only the member names above carry information it needs.
-keep,allowobfuscation class io.github.mvolkert.entryrecorder.data.server.**
-keep,allowobfuscation class io.github.mvolkert.entryrecorder.data.backup.AppBackup
-keep,allowobfuscation class io.github.mvolkert.entryrecorder.data.local.entity.**
-keep,allowobfuscation class io.github.mvolkert.entryrecorder.data.model.**
# The generic signature of `object : TypeToken<List<ServerRecordingDto>>() {}` is data Gson reads back off
# the anonymous subclass, so that subclass must not be shrunk away.
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# WorkManager constructs the app's workers reflectively from the WorkSpec, so their public constructor
# must survive even though nothing in the Kotlin source ever calls it.
-keepclassmembers class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
