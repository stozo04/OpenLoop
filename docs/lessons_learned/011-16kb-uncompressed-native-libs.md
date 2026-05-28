# Lesson 011 — Verify 16 KB alignment on *uncompressed* native libs, not a vacuous "compressed" pass

## What went wrong

While satisfying the Android 15+/16 **16 KB page size** requirement (Issue #7), the first `zipalign` check on the release APK looked like a pass:

```
lib/arm64-v8a/libimage_processing_util_jni.so (OK - compressed)
...
Verification successful
```

Exit code `0`, "Verification successful" — but every `.so` said **`(OK - compressed)`**. That is a *vacuous* pass. The app had `jniLibs { useLegacyPackaging = true }`, which stores native libraries **compressed** in the APK (extracted to disk at install). `zipalign -c -P 16` only checks the 16 KB page alignment of **uncompressed** entries, so for compressed `.so` it reports "OK" without verifying anything about 16 KB-mappability. You can believe you're compliant when the check proved nothing.

## Pattern

For `targetSdk 36` / 16 KB compliance, store native libs **uncompressed and page-aligned** so they map directly from the APK:

```kotlin
// app/build.gradle.kts → android { packaging { jniLibs { ... } } }
useLegacyPackaging = false   // the modern default; required for real 16 KB mapping. minSdk must be >= 23.
```

After flipping it, the same check shows a **real** pass — `(OK)` (no "compressed"), each `.so` at a 16384-byte (16 KB) multiple:

```
lib/arm64-v8a/libimage_processing_util_jni.so (OK)   # offset divisible by 16384
Verification successful
```

Two independent things must both be true for 16 KB compliance:
1. **Packaging:** libs uncompressed + 16 KB-zip-aligned in the APK (`useLegacyPackaging = false`; AGP zipaligns with build-tools 35+).
2. **The `.so` itself** must be built 16 KB ELF-aligned by the dependency — true for **CameraX >= 1.4.0** and recent **Media3**. Older CameraX (1.2/1.3) shipped 4 KB-aligned libs.

## Detection checklist

- Grep the build for legacy packaging, which makes the alignment check meaningless:
  ```
  grep -rn "useLegacyPackaging = true" app/build.gradle.kts
  ```
- Run the check against the **release** APK and read the per-`.so` lines, not just the exit code:
  ```
  <sdk>/build-tools/<ver>/zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release-unsigned.apk
  ```
  - `(OK - compressed)` → alignment is **unverified**; set `useLegacyPackaging = false`, rebuild, re-check.
  - `(OK)` with offsets divisible by 16384 → genuinely aligned.
- Cross-check a single lib's ELF alignment: `llvm-objdump -p <lib>.so | grep LOAD` should show `align 2**14` (16 KB), not `2**12`/`2**13`.
- A pure Java/Kotlin app (no `lib/` folder in the APK) is automatically compliant — this only matters when a dependency ships `.so` files (here: CameraX, Media3).

## Reference

- [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes) — Google Play requirement (native-lib apps targeting API 35+, since Nov 1 2025) and verification tooling.
- [`useLegacyPackaging`](https://developer.android.com/reference/tools/gradle-api/com/android/build/api/dsl/JniLibsPackaging) — AGP packaging DSL.
- Surfaced during the target-SDK-36 upgrade ([Issue #7](https://github.com/stozo04/OpenRang/issues/7)); see [`docs/active/007-target-sdk-upgrade/IMPLEMENTATION.md`](../active/007-target-sdk-upgrade/IMPLEMENTATION.md).
