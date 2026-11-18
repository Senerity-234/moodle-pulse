# Keep kotlinx.serialization models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.moodlepulse.app.model.**$$serializer { *; }
-keepclassmembers class com.moodlepulse.app.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.moodlepulse.app.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
