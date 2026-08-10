# OpenLoop R8 / ProGuard rules.
#
# CameraX and Media3 ship consumer ProGuard rules inside their AARs, so R8 applies the
# keeps those libraries need automatically. Jetpack Compose is R8-compatible out of the box.
# OpenLoop itself uses no reflection-based serialization, so no app-specific keeps are
# required today. Add rules below only when a release build or runtime behavior shows a need.

# com.google.android.play:review-ktx:2.0.2 compiles against a GMS annotation that ships in no
# runtime artifact, so R8 fails the release build on a missing class:
#   Missing class com.google.android.gms.common.annotation.NoNullnessRewrite
#     (referenced from ReviewManagerKtxKt$sam$...OnSuccessListener$0.onSuccess)
# It is a CLASS-retention annotation — nothing reads it at runtime — so suppressing the warning is
# the whole fix, not a workaround hiding a real missing dependency. This is verbatim what AGP
# generated in app/build/outputs/mapping/release/missing_rules.txt.
-dontwarn com.google.android.gms.common.annotation.NoNullnessRewrite
