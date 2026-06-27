# Keep Hilt generated entry points.
-keep class dagger.hilt.** { *; }
-keep class com.sapphire.**.Hilt_* { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Sapphire data models (serialized).
-keep class com.sapphire.domain.model.** { *; }
