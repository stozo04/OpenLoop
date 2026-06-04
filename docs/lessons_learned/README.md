# Lessons Learned

Distilled rules from past code reviews and bugs. **Every Claude Code session must read this folder at startup** — see `CLAUDE.md` for the mandate.

Each lesson follows the same shape:

- **What went wrong** — the specific mistake, with file/line where useful.
- **Pattern** — the rule going forward, with code snippets where mechanical.
- **Detection checklist** — what to grep / look for to catch the same mistake again.
- **Reference** — Google doc URL and the originating PR.

## Index

| # | Lesson | Origin |
|---|--------|--------|
| 001 | [Compose `Color()` expects exactly 8 hex digits](./001-compose-color-literal-format.md) | PR #5 |
| 002 | [Use `collectAsStateWithLifecycle()` for Flow collection in Compose](./002-lifecycle-aware-flow-collection.md) | PR #5 |
| 003 | [Wrap DataStore writes in try-catch(IOException)](./003-datastore-write-ioexception.md) | PR #5 |
| 004 | [Never pass Context to ViewModel methods; extract a Repository](./004-viewmodel-no-context-parameters.md) | PR #5 |
| 005 | [Track current Play Store target API level requirements](./005-play-store-target-api-level.md) | PR #5 |
| 006 | [Always show permission rationale before re-requesting](./006-permission-rationale-flow.md) | PR #5 |
| 007 | [A standards doc and the code in the same PR must agree](./007-standards-doc-must-match-code.md) | PR #5 |
| 008 | [JVM unit tests: real temp dirs for File, one shared TestDispatcher](./008-jvm-test-file-and-dispatcher-pitfalls.md) | Issue #11 |
| 009 | [TOML inline tables must be on a single line](./009-toml-inline-tables-single-line.md) | IDE inspection |
| 010 | [Markdown code fences are parsed by the IDE; keep snippets valid](./010-markdown-code-fences-are-inspected.md) | IDE inspection |
| 011 | [Verify 16 KB alignment on uncompressed native libs, not a "compressed" pass](./011-16kb-uncompressed-native-libs.md) | Issue #7 |
| 012 | [A camera-bound screen must stay on ONE composable call site across state transitions](./012-camera-bound-screen-single-call-site.md) | Slice 01 |
| 013 | [Media start calls: handle the failure return, catch only documented sync throwables, async errors come via callback](./013-media-start-failure-return-and-narrow-catch.md) | PR #19 |
| 014 | [The UI-state router `when` must be exhaustive with no `else` (extract a testable NavHost)](./014-state-router-when-exhaustive-no-else.md) | PR #19 |
| 015 | [Predictive back is default-on at target 36: gate a state-routed `BackHandler` on screens that can lose work](./015-predictive-back-state-routed-backhandler.md) | PR #19 |
| 016 | [Compose: defer high-frequency state reads behind `() -> T` lambdas read in the narrowest/draw scope](./016-compose-defer-high-frequency-state-reads.md) | PR #19 |
| 017 | [Instrumented tests can't use mockk; sweep every call site when a mock's return value gains meaning](./017-androidtest-no-mockk-and-sweep-meaningful-mock-returns.md) | PR #19 |
| 018 | [The boomerang seam-frame drop follows sequence position, not clip identity](./018-boomerang-seam-drop-follows-sequence-position.md) | Slice 03 |
| 019 | [Reversing through a decoder→encoder Surface: strip `KEY_ROTATION` on the decoder, re-stamp on the muxer](./019-reverse-rotation-strip-decoder-restamp-muxer.md) | Slice 02 |
| 020 | [Imported clips exercise codecs the camera never produces: tone-map HDR, prefer a HW encoder, cap resolution; a failed reverse must never wedge the editor](./020-imported-clips-hdr-codec-and-reverse-failure-recovery.md) | Slice 07 |
| 021 | [Don't downscale inside a decode→encode-Surface transcode by sizing the encoder Surface smaller than the decoder output (green corruption on the SW path); cap resolution in the Media3 render instead](./021-reverse-downscale-surface-mismatch.md) | Onboarding video |
| 022 | [Release CameraX when the PreviewView leaves composition, not only when the Activity stops](./022-release-camera-when-preview-leaves-composition.md) | Issue #36 |
| 023 | [A media pipeline stage must count its own output samples; a zero-frame stage can exit "cleanly"](./023-media-pipeline-stages-must-count-output-samples.md) | PR #62 (S23 zero-frame wedge) |

## Adding a new lesson

When a PR review surfaces a new pattern worth preserving:

1. Number the file as the next sequential ID (zero-padded to 3 digits).
2. Use a kebab-case slug derived from the rule.
3. Add an entry to the index above.
4. Commit the lesson alongside the fix it documents.

A lesson is only worth writing down if it had prevented the original mistake — abstract advice doesn't qualify. Be specific. Cite file paths, grep patterns, and the exact Google doc URL.
