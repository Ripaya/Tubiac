# Preserve full bytecode symbols for dynamically loaded Tachiyomi/Mihon extension APKs
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
-keep,allowshrinking class * extends uy.kohesive.injekt.api.FullTypeReference

# Keep Kotlin stdlib & core extension runtime dependencies unstripped in minified APKs
-keep,includedescriptorclasses class kotlin.** { *; }
-keep,includedescriptorclasses interface kotlin.** { *; }
-keep,includedescriptorclasses enum kotlin.** { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.** { *; }
-keep,includedescriptorclasses interface kotlinx.coroutines.** { *; }
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }
-keep,includedescriptorclasses interface kotlinx.serialization.** { *; }

# Keep all Tachiyomi/Mihon extension API classes, inheritance hierarchy, constructors, and default-arg stubs
-keep,includedescriptorclasses class eu.kanade.tachiyomi.** { *; }
-keep,includedescriptorclasses interface eu.kanade.tachiyomi.** { *; }
-keep,includedescriptorclasses enum eu.kanade.tachiyomi.** { *; }
-keep class keiyoushi.** { *; }
-keep,includedescriptorclasses class uy.kohesive.injekt.** { *; }
-keep,includedescriptorclasses interface uy.kohesive.injekt.** { *; }

# Keep networking, parsing, JS, RxJava, and preference libraries used at extension runtime
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
-keep class app.cash.quickjs.** { *; }
-keep class com.squareup.zstd.** { *; }
-keep class tube.maniac.core.extension.** { *; }

-keepclassmembers class * implements java.io.Serializable {
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

-keepclassmembers class rx.internal.util.unsafe.*ArrayQueue*Field* {
    long producerIndex;
    long consumerIndex;
}
-keepclassmembers class rx.internal.util.unsafe.BaseLinkedQueueProducerNodeRef {
    rx.internal.util.atomic.LinkedQueueNode producerNode;
}
-keepclassmembers class rx.internal.util.unsafe.BaseLinkedQueueConsumerNodeRef {
    rx.internal.util.atomic.LinkedQueueNode consumerNode;
}
-keepclasseswithmembers class okhttp3.MultipartBody$Builder { *; }

-dontwarn kotlin.**
-dontwarn keiyoushi.**
-dontwarn eu.kanade.tachiyomi.**
-dontwarn uy.kohesive.injekt.**
-dontwarn rx.**
-dontwarn org.jsoup.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn kotlinx.serialization.**
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.preference.**
-dontwarn app.cash.quickjs.**
-dontwarn sun.misc.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# Keep kotlinx.serialization generated serializers & generic signatures
-dontnote kotlinx.serialization.**
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
