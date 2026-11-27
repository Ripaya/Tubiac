# Preserve full bytecode symbols for dynamically loaded Tachiyomi/Mihon extension APKs
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Keep all Tachiyomi/Mihon extension API classes, inheritance hierarchy, constructors, and default-arg stubs
-keep,includedescriptorclasses class eu.kanade.tachiyomi.** { *; }
-keep,includedescriptorclasses interface eu.kanade.tachiyomi.** { *; }
-keep,includedescriptorclasses enum eu.kanade.tachiyomi.** { *; }
-keep class keiyoushi.** { *; }
-keep,includedescriptorclasses class uy.kohesive.injekt.** { *; }
-keep,includedescriptorclasses interface uy.kohesive.injekt.** { *; }

# Keep extension runtime dependencies unstripped in host ClassLoader
-keep class rx.** { *; }
-keep interface rx.** { *; }
-keep class org.jsoup.** { *; }
-keep interface org.jsoup.** { *; }
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-keep interface okio.** { *; }
-keep class androidx.preference.** { *; }
-keep interface androidx.preference.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep interface kotlinx.serialization.** { *; }
-keep class tube.maniac.core.extension.** { *; }

-dontwarn keiyoushi.**
-dontwarn eu.kanade.tachiyomi.**
-dontwarn uy.kohesive.injekt.**
-dontwarn rx.**
-dontwarn org.jsoup.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn kotlinx.serialization.**
-dontwarn androidx.preference.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# Keep kotlinx.serialization generated serializers & generic signatures
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class tube.maniac.core.model.** { *; }

# Preserve Room entities & WorkManager workers
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.Worker { *; }
