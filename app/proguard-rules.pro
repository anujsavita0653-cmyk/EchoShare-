# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified in the SDK.

# Keep Media3/ExoPlayer classes
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }

# Keep Gson models
-keep class com.smartchoice.echoshare.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep service classes
-keep class com.smartchoice.echoshare.service.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
