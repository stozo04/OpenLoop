# Guides

Plain-English how-to docs for OpenLoop concepts and workflows. Start here when you need
step-by-step instructions rather than architecture specs.

| Guide | When to read it |
|-------|-----------------|
| [`oem-regression-testing.md`](oem-regression-testing.md) | **API 34 / Samsung / LG regression lanes** — emulator sweeps, Robolectric, RTL, LG fault injection |
| [`robolectric-testing-explained.md`](robolectric-testing-explained.md) | What Robolectric is, repo setup, `@Config(sdk=[...])`, prioritized test targets |
| [`samsung-rtl-steps.md`](samsung-rtl-steps.md) | One-time Samsung Remote Test Lab setup (RDB, adb PATH, manual smoke) |
| [`jetpack-datastore-explained.md`](jetpack-datastore-explained.md) | What DataStore is; inspect/reset onboarding on a device |

Testing strategy and inventory: [`../TEST_COVERAGE.md`](../TEST_COVERAGE.md).

Agent-runnable E2E skills: `.claude/skills/run-e2e/` and `.claude/skills/run-e2e-pixel-sweep/`.
