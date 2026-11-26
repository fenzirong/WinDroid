# ---------------------------------------------------------------------------
# WinDroid - R8 / ProGuard rules
# ---------------------------------------------------------------------------

# Keep the shared domain contracts: Wave 2 modules reference them by name and
# kotlinx.serialization generates companions for the @Serializable types.
-keep class com.windroid.app.core.model.** { *; }
-keepclassmembers class com.windroid.app.core.model.** { *; }

# Keep generated serializers, their companions and the @SerialName mappings.
-keepclassmembers class **$$serializer {
    *;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.windroid.app.**$$serializer { *; }
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations, RuntimeVisibleTypeAnnotations

# kotlinx.serialization JSON runtime helpers.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Kotlin metadata / coroutines internals that R8 tends to strip.
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Enum values are persisted by name inside container config files.
-keepclassmembers enum com.windroid.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# App entry points referenced from the manifest.
-keep class com.windroid.app.WinDroidApp { *; }
-keep class com.windroid.app.MainActivity { *; }
-keep class com.windroid.app.SessionActivity { *; }
