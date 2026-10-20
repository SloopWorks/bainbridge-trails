# R8 rules for the release build (androidApp release: isMinifyEnabled + isShrinkResources).
#
# Most libraries here ship consumer rules in their AARs/JARs (kotlinx-serialization, kotlinx-coroutines, Ktor,
# OkHttp, MapLibre Native, androidx.credentials, Compose). The rules below cover what is specific to this app and
# restate the library-critical ones so a library dropping its consumer file does not silently break release.
# Verify after changes: assembleRelease, install, and walk Map / Trails / Trail detail / Me / Record / Settings
# with `adb logcat -b crash` clean (see app/AGENTS.md).

# Readable release stack traces (mapping.txt is in androidApp/build/outputs/mapping/release/).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- kotlinx-serialization -------------------------------------------------------------------------------------
# DTOs (net/ApiDtos.kt), settings/session/sync/coverage/tracking files and the type-safe navigation routes are all
# @Serializable. Serial names come from the compiler plugin as string constants, so renaming the classes is safe;
# what must survive is the generated serializer plumbing that is looked up reflectively (Companion.serializer(),
# $serializer, the INSTANCE of objects, and the cached serializer field on objects/sealed classes).
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.internal.ClassValueReferences

-if @kotlinx.serialization.Serializable class com.bainbridgetrails.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class com.bainbridgetrails.** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class com.bainbridgetrails.** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class com.bainbridgetrails.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
    private static final kotlinx.serialization.KSerializer[] $childSerializers;
}
-keep,includedescriptorclasses class com.bainbridgetrails.**$$serializer { *; }

# ---- Navigation type-safe routes ---------------------------------------------------------------------------------
# composable<T>() / toRoute<T>() resolve the route's serializer (serializer<T>()) and build the route pattern from
# its descriptor. Keep the route classes whole: they are tiny, and the nav graph is keyed on them.
-keep class com.bainbridgetrails.nav.*Route { *; }
-keep class com.bainbridgetrails.nav.*Route$* { *; }
-keepnames class com.bainbridgetrails.nav.Route

# ---- Coroutines ---------------------------------------------------------------------------------------------------
# ServiceLoader-based dispatchers / exception handlers (kotlinx-coroutines ships equivalent rules).
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.debug.**
-dontwarn java.lang.instrument.**
-dontwarn sun.misc.SignalHandler

# ---- Ktor (OkHttp engine) -----------------------------------------------------------------------------------------
# The engine is chosen explicitly in code, but Ktor still discovers containers via ServiceLoader on JVM/Android.
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**

# ---- MapLibre Native (JNI) ----------------------------------------------------------------------------------------
# libmaplibre.so calls back into Java by class/method/field name; nothing under org.maplibre.android may be renamed
# or removed. maplibre-compose's Android runtime is the Kotlin side of that bridge.
-keep class org.maplibre.android.** { *; }
-keep interface org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-keep class org.maplibre.compose.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-dontwarn org.maplibre.**
-dontwarn com.google.gson.**

# ---- Compose resources ---------------------------------------------------------------------------------------------
# Compose Multiplatform resources are read from the APK's assets (composeResources/<package>/...) by path, which R8
# and the resource shrinker leave alone. The generated Res accessors are ordinary code (no reflection).
-keep class com.bainbridgetrails.resources.** { *; }

# ---- Credential Manager / Sign in with Google -------------------------------------------------------------------
# The credentials library instantiates its Play services provider reflectively (class name in the manifest meta-data).
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn androidx.credentials.**
-dontwarn com.google.android.libraries.identity.googleid.**

# ---- Misc ------------------------------------------------------------------------------------------------------------
# Foreground recording service is referenced from the manifest (AAPT keeps it); keep its name stable in crash logs.
-keepnames class com.bainbridgetrails.tracking.RecordingService
