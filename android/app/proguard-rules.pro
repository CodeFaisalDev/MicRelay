# MicRelay ProGuard Rules

# Keep MediaCodec and AudioRecord JNI callbacks
-keepclassmembers class * {
    native <methods>;
}

# Keep Kotlin Coroutines and Flow
-keepattributes *Annotation*,InnerClasses,EnclosingMethod

# Keep CameraX internal components
-dontwarn androidx.camera.**
-keep class androidx.camera.** { *; }
