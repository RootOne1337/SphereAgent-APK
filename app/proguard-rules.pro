# SphereAgent ProGuard Rules
# Правила для production сборки

# === GENERAL RULES ===

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable

# Hide original source file names
-renamesourcefileattribute SourceFile

# === KOTLIN ===
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# === OKHTTP ===
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.codehaus.mojo.animal_sniffer.*

# === RETROFIT ===
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# === HILT ===
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# === COMPOSE ===
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# === APP SPECIFIC ===

# Keep data classes
-keep class com.sphere.agent.core.** { *; }
-keep class com.sphere.agent.network.** { *; }
-keep class com.sphere.agent.data.** { *; }

# Keep services
-keep class com.sphere.agent.service.** { *; }

# Keep view models
-keep class com.sphere.agent.ui.viewmodel.** { *; }

# === ANDROID ===
-keep class * extends android.app.Service
-keep class * extends android.accessibilityservice.AccessibilityService
-keep class * extends android.content.BroadcastReceiver
