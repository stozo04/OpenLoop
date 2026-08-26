# OpenLoop R8 / ProGuard rules.
#
# CameraX and Media3 ship consumer ProGuard rules inside their AARs, so R8 applies the
# keeps those libraries need automatically. Jetpack Compose is R8-compatible out of the box.
# OpenLoop itself uses no reflection-based serialization, so no app-specific keeps are
# required today. Add rules below only when a release build or runtime behavior shows a need.

# review-ktx references a CLASS-retention GMS annotation that ships in no runtime artifact, so R8
# fails the release build without this. Verbatim from release/missing_rules.txt — not a real dep.
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite

# MediaPipe Tasks (hand landmarks — docs/PRD-lens-hand-flick.md) ships NO consumer rules: the
# tasks-vision / tasks-core 1.0.0 AARs carry no proguard.txt (checked). Its Java side is reached
# from libmediapipe_tasks_jni.so by name (packets, graphs, the auto-value result types), so R8
# must keep it whole. Protobuf-lite is its wire format and is reflected on the same way.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
