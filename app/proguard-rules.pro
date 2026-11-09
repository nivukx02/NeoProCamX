# NeoProCamX ProGuard rules

# Keep Camera2 API
-keep class android.hardware.camera2.** { *; }
-keepclassmembers class android.hardware.camera2.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }

# Keep data classes used in StateFlow
-keep class com.neoproc.neoproccamx.camera.CameraParams { *; }
-keep class com.neoproc.neoproccamx.camera.CaptureResultData { *; }
-keep class com.neoproc.neoproccamx.recording.RecordingConfig { *; }
-keep class com.neoproc.neoproccamx.focus.FaceData { *; }
-keep class com.neoproc.neoproccamx.focus.FocusPoint { *; }
-keep class com.neoproc.neoproccamx.color.CubeLUT { *; }
-keep class com.neoproc.neoproccamx.ai.TrackedSubject { *; }

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelables
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep JNI entry points that are called from native code.
-keepclasseswithmembers class * {
    native <methods>;
}

# MediaRecorder / MediaCodec
-keep class android.media.MediaRecorder { *; }
-keep class android.media.MediaCodec { *; }
-keep class android.media.MediaMuxer { *; }

# OpenGL
-keep class android.opengl.** { *; }

# DngCreator
-keep class android.hardware.camera2.DngCreator { *; }

# Fragment
-keep public class * extends androidx.fragment.app.Fragment

# ViewModel
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# MediaPipe / Protobuf — R8 strips reflective protobuf fields that MediaPipe needs at runtime
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
-keepclassmembers class com.google.mediapipe.framework.** { *; }
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite$* { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageV3 { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessage { *; }

# Keep protobuf field accessors (the "platform_" and similar reflection-based lookups)
-keepclassmembers class * {
    <fields>;
}

# Google Flogger (used by MediaPipe internally)
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# TensorFlow Lite
-keep class org.tensorflow.** { *; }
-keep class org.tensorflow.lite.** { *; }

# Suppress warnings for optional dependencies
-dontwarn org.slf4j.**
-dontwarn javax.**

# Missing MediaPipe protobuf classes (not present at runtime but referenced)
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate

# #22 fix: strip verbose logging from RELEASE builds. Per-frame Log.v/d/i calls on the preview/record/
# analysis hot paths execute their string concatenation even in shipped builds, adding avoidable CPU +
# allocation pressure during 4K recording. -assumenosideeffects lets R8 (release uses
# proguard-android-optimize.txt, so optimizations are on) delete these calls AND their argument
# expressions. Log.w and Log.e are deliberately KEPT so warnings/errors remain available for field crash
# diagnosis. Debug builds do not minify, so all logging still works there.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static boolean isLoggable(java.lang.String, int);
}
