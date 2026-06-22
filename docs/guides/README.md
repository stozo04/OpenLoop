# Guides

Plain-English how-to docs for OpenLoop concepts and workflows. Start here when you need
step-by-step instructions rather than architecture specs.

| Guide | When to read it |
|-------|-----------------|
| [`oem-regression-testing.md`](oem-regression-testing.md) | **API 34 / Samsung / LG regression lanes** — emulator sweeps, Robolectric, RTL, LG fault injection |
| [`robolectric-testing-explained.md`](robolectric-testing-explained.md) | What Robolectric is, repo setup, `@Config(sdk=[...])`, when to use it vs device tests |
| [`robolectric-test-catalog.md`](robolectric-test-catalog.md) | **Inventory of all Robolectric/JVM-framework tests** — phases, run commands, method list, device complements |
| [`samsung-rtl-steps.md`](samsung-rtl-steps.md) | One-time Samsung Remote Test Lab setup (RDB, adb PATH, manual smoke) |
| [`jetpack-datastore-explained.md`](jetpack-datastore-explained.md) | What DataStore is; inspect/reset onboarding on a device |
| [`reverse-video-research.md`](reverse-video-research.md) | Locked two-pass MediaCodec reverse algorithm — cited in `VideoReverser.kt` |

Testing strategy and inventory: [`../TEST_COVERAGE.md`](../TEST_COVERAGE.md).

Agent-runnable E2E skills: `.claude/skills/run-e2e/` and `.claude/skills/run-e2e-pixel-sweep/`.
