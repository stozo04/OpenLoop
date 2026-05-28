# OpenRang R8 / ProGuard rules.
#
# CameraX and Media3 ship consumer ProGuard rules inside their AARs, so R8 applies the
# keeps those libraries need automatically. Jetpack Compose is R8-compatible out of the box.
# OpenRang itself uses no reflection-based serialization, so no app-specific keeps are
# required today. Add rules below only when a release build or runtime behavior shows a need.
