# Guides

Durable reference for OpenLoop's testing lanes and media algorithms. Start here when you need
repo-specific procedure rather than architecture specs.

General Android/Kotlin/library concepts are **not** mirrored here — web-search
`developer.android.com` instead (see `CLAUDE.md` → Critical Rule).

| Guide | When to read it |
|-------|-----------------|
| [`oem-regression-testing.md`](oem-regression-testing.md) | **API 34 / Samsung / LG regression lanes** — emulator sweeps, Robolectric, RTL, LG fault injection |
| [`robolectric-testing-explained.md`](robolectric-testing-explained.md) | When to use Robolectric, run commands, and **what must never move to it** (the media pipeline) |
| [`samsung-rtl-steps.md`](samsung-rtl-steps.md) | One-time Samsung Remote Test Lab setup (RDB, adb PATH, manual smoke) |
| [`reverse-video-research.md`](reverse-video-research.md) | Locked two-pass MediaCodec reverse algorithm — cited in `VideoReverser.kt` |
| [`android-skills.md`](android-skills.md) | **Google's `android/skills` plugin** — how the 21 skills reach Claude, forcing one (`/android-skills:camerax`), project-only scoping, the r8-analyzer / Play-policy flows, updates |
| [`elvis-asset-processing.md`](elvis-asset-processing.md) | **Photoreal lens-art bitmap workflow** — chroma-key → trim → measure `artAspect` → WebP, the steps that produced the Elvis shades/pompadour assets in `drawable-nodpi/` |
| [`localization.md`](localization.md) | **Why every user-facing string must live in `strings.xml`** — Play's free Gemini app-strings translation reads only that file. Read before adding any UI copy |

Testing strategy and inventory: [`../TEST_COVERAGE.md`](../TEST_COVERAGE.md).

Agent-runnable E2E skills: `.claude/skills/run-e2e/` and `.claude/skills/run-e2e-pixel-sweep/`.
