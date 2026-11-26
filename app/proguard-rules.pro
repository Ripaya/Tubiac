# Preserve Tachiyomi/Mihon extension API stubs loaded dynamically via PathClassLoader
-keep class eu.kanade.tachiyomi.** { *; }
-keep interface eu.kanade.tachiyomi.** { *; }
-keep class uy.kohesive.injekt.** { *; }
-keep class rx.** { *; }
-keep class org.jsoup.** { *; }

# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class tube.maniac.core.model.**$$serializer { *; }
-keepclassmembers class tube.maniac.core.model.** {
    *** Companion;
}
