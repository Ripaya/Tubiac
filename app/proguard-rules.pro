# Preserve Tachiyomi/Mihon extension API stubs & networking helpers loaded dynamically via PathClassLoader
-keep class eu.kanade.tachiyomi.** { *; }
-keep interface eu.kanade.tachiyomi.** { *; }
-keep class keiyoushi.** { *; }
-keep class uy.kohesive.injekt.** { *; }
-keep interface uy.kohesive.injekt.** { *; }
-keep class rx.** { *; }
-keep class org.jsoup.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class androidx.preference.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class tube.maniac.core.extension.** { *; }

-dontwarn keiyoushi.**
-dontwarn eu.kanade.tachiyomi.**
-dontwarn uy.kohesive.injekt.**
-dontwarn rx.**
-dontwarn org.jsoup.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# Keep kotlinx.serialization generated serializers & generic signatures
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
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
