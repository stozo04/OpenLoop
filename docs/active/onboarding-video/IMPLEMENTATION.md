# Onboarding page-1 video — Implementation

> Branch: `feature/loop-1` · PR: [#34](https://github.com/stozo04/OpenLoop/pull/34) · Status: built, in review
> Belongs in `docs/active/` until merged, then moves to `docs/completed/`.

## Problem statement

Onboarding page 1 ("No Subscriptions & No Ads") showed a static drawable (`onboarding_skater`). The app
*is* a video-loop tool, so the first thing a new user sees should be the product itself — a looping
boomerang — not a still image. This was produced alongside the first real loop generated from an
imported clip (which also surfaced the trim-preview and reverse-corruption bugs fixed in the same PR).

## Scope

**In**
- Page 1 autoplays a muted, looping boomerang clip bundled at `app/src/main/res/raw/onboarding_loop_1.mp4`
  (540×960, ~2.8 MB), cropped to fill the frosted card.
- Playback is via ExoPlayer in an `AndroidView` (`OnboardingVideoCard`), mirroring the gallery's
  `LoopingVideoOverlay`, but inline and silent.
- Only the **settled** pager page decodes; adjacent composed-but-off-screen pages stay paused.
- Playback is **lifecycle-aware**: pauses on `ON_STOP`, resumes on `ON_START`, released on
  leave-composition.

**Out**
- Pages 2–3 stay static drawables.
- No per-device clip selection, no remote/streamed onboarding media, no audio.
- No runtime still-image fallback on decode failure (the dark frosted card is the failure state); the
  drawable is only used as the `@Preview` / inspection-mode fallback.

## Architecture

Fits `OnboardingScreen.kt` (PRD §4.4) with no ViewModel or state-machine change — onboarding is a pure
UI screen.

- `OnboardingPage` gains an optional `videoRawRes: Int?`. When non-null **and** not in inspection mode,
  `OnboardingPageContent` renders `OnboardingVideoCard(rawResId, playing = isActivePage)`; otherwise the
  existing `Image(drawableRes)`.
- `OnboardingVideoCard`:
  - References the clip via the non-deprecated `android.resource://<pkg>/<resId>` URI
    (`ContentResolver.SCHEME_ANDROID_RESOURCE` + KTX `String.toUri()`), which Media3's
    `DefaultDataSource` routes to its raw-resource reader (replaces the deprecated
    `RawResourceDataSource.buildRawResourceUri`).
  - `REPEAT_MODE_ALL`, `volume = 0f`, `RESIZE_MODE_ZOOM` (crop-to-fill).
  - `LifecycleStartEffect(playing)` sets `playWhenReady = playing` on `ON_START` and `false` on
    `ON_STOP` ([release/stop playback in `onStop` on API 24+](https://developer.android.com/media/implement/playback-app)).
  - `DisposableEffect` releases the player on leave-composition.
  - Carries a `contentDescription` so TalkBack labels the otherwise-bare `PlayerView` (the clip is a
    decorative demo; the page title is the real label).

## Implementation steps (as built)

1. Bundle `res/raw/onboarding_loop_1.mp4`.
2. Add `videoRawRes` to `OnboardingPage`; set it on page 1.
3. Add `OnboardingVideoCard`; route page content through it when `videoRawRes != null && !LocalInspectionMode.current`.
4. Gate playback on the settled page (`pagerState.currentPage == page`).
5. Make playback lifecycle-aware (`LifecycleStartEffect`) and released on dispose.

## Testing plan

- Existing `OnboardingNavigationTest` / `OnboardingScreenTest` still green (no API change to navigation).
- `@Preview`s render the static drawable (inspection-mode fallback) — no ExoPlayer in preview.
- Manual / on-device: page 1 plays the looping clip; backgrounding pauses it; returning resumes; swiping
  to pages 2–3 stops decode of page 1.
- Compose UI tests can't assert real decode; coverage here is the inspection-mode fallback + on-device QA.

## Acceptance criteria

- [x] Page 1 autoplays the muted looping boomerang on-device, cropped to the card.
- [x] Only the settled page decodes.
- [x] Playback pauses when the app is backgrounded and resumes on return.
- [x] Player is released (no leak) when onboarding leaves the composition.
- [x] `@Preview` shows the static drawable, not a blank `AndroidView`.
- [x] `:app:lintDebug` reports no new issues; no deprecated-API usage introduced.
