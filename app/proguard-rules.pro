# kotlinx.serialization keeps generated serializers through the plugin's own rules.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class ink.courtpace.index.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class ink.courtpace.index.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
