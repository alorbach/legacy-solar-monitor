# Room entities and enums (stored as names / used by generated DAOs).
-keep class com.alorbach.solarmonitor.data.model.** { *; }
-keepclassmembers enum com.alorbach.solarmonitor.data.model.** { *; }
-keep class com.alorbach.solarmonitor.data.local.** { *; }

# Glance app-widget receivers must keep their public no-arg constructors.
-keep class com.alorbach.solarmonitor.widget.** { *; }
-keep class androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }

# JSch (SFTP) and Commons Net (FTP) use reflection for protocol internals.
-keep class com.jcraft.jsch.** { *; }
-keep class org.apache.commons.net.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.apache.commons.net.**
-dontwarn org.ietf.jgss.**
-dontwarn org.bouncycastle.**

# EncryptedSharedPreferences / Tink
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Kotlin coroutines (Main dispatcher + exception handlers loaded by ServiceLoader).
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.coroutines.flow.**
