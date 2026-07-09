# E2E proof — pinch-to-zoom during live capture (2026-07-09)

Proof run for the `feature/capture-pinch-zoom` PR (see `docs/PRD-capture-zoom.md`). Two emulators;
pinches injected as raw two-pointer `sendevent` streams on `/dev/input/event2`
(`virtio_input_multi_touch_1`), spread from a 200 px gap to ~1000 px and **held** so the chip is
mid-gesture in the screenshots. `adb shell input` cannot multi-touch (PRD §6.4), and event
injection needs `adb root`, so the pinch screenshots come from the rootable `google_apis`
Pixel 6 AVD; the Play-image Pixel 8 AVD hosted the instrumented suite and launch proof.

| Proof | Device | Screenshot |
|-------|--------|------------|
| Cold launch to camera, no FATAL in logcat, idle viewfinder at 1x | Pixel 8 AVD (API 37, Play image) | [idle](2026-07-09-capture-zoom-idle-pixel8.png) |
| Pinch on **back** lens: chip appears; CameraX reports range **[1.0, 1.0]** on this emulator, so the clamp pins at `1.0x` — D4 range-honesty exercised for the degenerate case | Pixel 6 AVD (API 37, google_apis) | [back clamped](2026-07-09-capture-zoom-back-clamped-1x-pixel6.png) |
| Pinch on **front** lens: range **[1.0, 12.93]**, chip tracks live to `2.1x`, capture request carries `android.control.zoomRatio=2.140218` (CXCP logcat), viewfinder visibly zoomed | Pixel 6 AVD (API 37, google_apis) | [front 2.1x](2026-07-09-capture-zoom-front-2.1x-pixel6.png) |

Also verified in logcat during the run:

- `CameraManager: Zoom range [1.0, 1.0], ratio 1.0` (back bind) then
  `Zoom range [1.0, 12.931958], ratio 1.0` (after flip) — the range-discovery log fires once per
  bind/range change, and the post-flip `ratio 1.0` confirms D3 reset-on-rebind.
- Repeated `UseCaseCameraRequestControlImpl#setParametersAsync … zoomRatio=…` entries during the
  pinch stream — gesture ticks reach the capture session with **no unbind/rebind** and no
  `ERROR_SOURCE_INACTIVE` / `Recorder … STOPPING` (Lesson 012 signature absent).

**Emulator finding worth knowing:** the emulator's *back* virtual camera advertises
`zoomRatioRange [1.0, 12.93]` in `dumpsys media.camera`, but CameraX's `ZoomState` for the bound
back camera reports `[1.0, 1.0]` — only the *front* camera exposes the usable digital range to
CameraX here. Encoded-output framing, ramp smoothness, and OEM behavior remain real-hardware
checks (PRD §6.3 matrix in the PR).
