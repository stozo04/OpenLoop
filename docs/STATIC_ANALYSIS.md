# Static Analysis — Reproducing Android Studio's "Inspect Code" as a merge gate

This is OpenLoop's plan and runbook for running the same checks Android Studio's **Analyze →
Inspect Code** produces, headlessly, and folding them into the PR-merge gate alongside the
[`pr-reviewer`](../.claude/skills/pr-reviewer/SKILL.md) standards review.

Last verified: 2026-08-20 · AGP 9.3.1 · Android Studio at `C:\Program Files\Android\Android Studio`

---

## The key insight: "Inspect Code" is two engines

Android Studio's single "Inspect Code" action is really **two analysis engines stacked**, and
they differ enormously in how headless-runnable they are. OpenLoop treats them as two tiers.

| Engine | Catches (examples from a real Inspect Code run) | Headless? |
|--------|--------------------------------------------------|-----------|
| **1. Android Lint** | *Correctness*: Obsolete Gradle dependency, Newer library versions available, Target SDK not latest. *Performance*: `mipmap-anydpi-v26` unnecessary (`ObsoleteSdkInt`). *Usability*: image in density-independent drawable folder (`IconLocation`), monochrome icon not defined, launcher silhouette, duplicated icons. | ✅ **Yes** — `./gradlew :app:lintDebug`, no IDE needed |
| **2. IntelliJ inspections + Grazie** | Kotlin *redundant constructs*, Java *declaration redundancy*, *Markdown* table formatting / numbered lists / **unresolved file references**, the Markdown "Annotator" parse errors ("Expecting an element"), and *Proofreading* (grammar, typos, style). | ⚠️ **IDE-only** — needs `inspect.bat`; slow; not reproducible by lint or standalone OSS tools (esp. Grazie grammar) |

---

## Tier 1 — Android Lint (automated gate, runs on every review)

Lint is deterministic and CI-safe, so it is wired directly into the `pr-reviewer` skill
(Phase 3.5) and is a **hard merge gate**: zero new lint **errors** to merge.

### Configuration (already in `app/build.gradle.kts`)

```kotlin
android {
    lint {
        xmlReport = true          // machine-readable — the skill parses this
        htmlReport = true         // human-readable companion for local triage
        checkDependencies = true  // lint included-module code too
        // No baseline — see "No baseline" below.
        abortOnError = false      // the skill decides the verdict, not the build
        warningsAsErrors = false  // warnings surface at WARNING/REC, not as build failures
    }
}
```

### Running it

```powershell
# 1. Point Java at a JDK (the bundled Studio JBR works):
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # macOS: see README
# 2. Run lint:
.\gradlew.bat :app:lintDebug
```

- Reports: `app/build/reports/lint-results-debug.xml` (+ `.html`).
- **Verifying the result honestly:** check the *real* exit code, not a piped one. A genuinely
  clean run prints `BUILD SUCCESSFUL` with exit `0` and **zero `severity="Error"` entries** in the
  XML. Warnings are expected and are triaged by severity (below), not treated as failures.

### No baseline

**There is no `lint-baseline.xml`, and adding one back needs a reason.** The file existed from when
this gate was added (the repo carried ~294 pre-existing inspection items, of which 11 were
lint-detectable) until 2026-08-17, when all 11 were **fixed** rather than carried:

| Entries | Issue | How it was cleared |
|---|---|---|
| 2 | `MonochromeLauncherIcon` | Added a `<monochrome>` layer — themed icons on Android 13+ (a real product gap, not just a lint nag) |
| 2 + 1 | `IconLauncherShape`, `IconDuplicates` | Deleted `mipmap-xxxhdpi/` — legacy pre-API-26 bitmaps, unreachable at minSdk 26 |
| 1 | `ObsoleteSdkInt` | `mipmap-anydpi-v26/` → `mipmap-anydpi/` |
| 1 | `IconLocation` | `onboarding_skater.jpg` deleted — it only ever rendered under Compose `@Preview`, and shipped 649 KB to do it (owner's call) |
| 1 | `UnusedAttribute` | Scoped `tools:ignore` **at the source** in `AndroidManifest.xml`, with the reason next to the attribute |
| 2 + 1 | `GradleDependency`, `NewerVersionAvailable` | Bumped activity-compose, datastore-preferences, kotlinx-coroutines-test to current stable |

An **empty** baseline is worse than none: it reproduces the "created with a different
target/variant" noise on every `lintVitalRelease` and stands as an invitation to regenerate.

> ⚠️ **If you reintroduce a baseline, understand what it costs.** Deleting the file and re-running
> lint regenerates it — which *silently swallows every issue currently in the tree*, including ones
> a PR just introduced. An August 2026 pass found the baseline at 18 entries with 7 not matching;
> a regenerate would have produced **37** — swallowing 26 live warnings, including three
> `InlinedApi` hits in `MainActivity.kt`. **Prefer suppressing at the source** (`tools:ignore`,
> `@Suppress`) with a comment giving the reason, so the justification lives next to the code
> instead of in an opaque XML blob.

#### "N errors/warnings were listed in the baseline file but not found in the project"

Kept here because it is the single most misread line lint emits, and it will reappear the moment
anyone adds a baseline back.

This is **not** N findings in your code — it means N *baseline entries* no longer match anything.
Read it as bookkeeping, never as a failure. Three causes hide behind it:

- **Wrong variant.** If the line is followed by `Creation variant: lint / Current variant:
  lintVitalRelease`, it is pure noise: `lintVital` runs only the **fatal-severity** subset against
  the release variant, so it cannot find warning-severity entries and reports every one of them as
  "not found". Nothing is fixed. Check with `:app:lintDebug`, which uses the full check set.
- **Genuinely fixed** — the issue is gone. The entry is dead weight.
- **Message drift** — the issue is still live, but the entry embeds a *moving* value. Every
  `GradleDependency` / `NewerVersionAvailable` / `AndroidGradlePluginVersion` message ends in
  `…is available: <version>`, which changes whenever upstream publishes. These entries
  self-invalidate on a schedule nobody controls, and the issue immediately resurfaces as a "new"
  warning under the updated message. **This is why those two checks were never a good fit for a
  baseline** — and why clearing them meant upgrading the dependencies, not re-snapshotting.

If a baseline does exist, **prune, don't regenerate**: drop only the non-matching entries. That edit
cannot hide anything, because a non-matching entry was suppressing nothing. Verify by confirming the
`filtered by baseline` count is unchanged.

### Severity mapping (lint → review verdict)

| Lint severity / category | Review severity |
|--------------------------|-----------------|
| `Error` / `Fatal` | **FAIL** |
| `Warning` in Correctness / Security / Performance (`OldTargetApi`, `GradleDependency`, `NewerVersionAvailable`, …) | **WARNING** |
| `Warning` in Usability / i18n / icons | **RECOMMENDATION** |
| `Hint` / `Informational` (e.g. `LintBaseline`) | ignored — not a finding |

---

## Tier 2 — IDE inspections + proofreading (faithful, run locally before merge)

This is the **only** faithful reproduction of the Kotlin-redundancy, Markdown, and **proofreading**
findings — because it is literally the same engine Android Studio uses, run headless against the
committed inspection profile. It needs Android Studio installed and is slow (it boots a headless
IDE instance), so it is **not** part of the automated skill run. Instead, the author runs it
locally before opening/merging a PR, and the merge policy requires it to be clean.

### Running it

```powershell
& "C:\Program Files\Android\Android Studio\bin\inspect.bat" `
  "C:\Users\gates\Personal\OpenLoop" `
  "C:\Users\gates\Personal\OpenLoop\.idea\inspectionProfiles\Project_Default.xml" `
  "C:\Users\gates\Personal\OpenLoop\build\inspection-results" `
  -v2 -d "C:\Users\gates\Personal\OpenLoop"
```

- **Args:** project path · inspection profile · output dir · `-v2` (verbose) · `-d` (scope).
- Point `-d` at the **repo root**, not just `app/src`, so it inspects `docs/` too — that's where
  the Markdown "Annotator", unresolved-file-reference, and proofreading findings live (and where
  the project already cares — see [Lesson 009](lessons_learned/009-toml-inline-tables-single-line.md)
  / [Lesson 010](lessons_learned/010-markdown-code-fences-are-inspected.md)).
- Output: one XML file per inspection in the output dir. Open in Studio or read as text.
- The committed `.idea/inspectionProfiles/Project_Default.xml` only serializes the Compose-Preview
  inspections explicitly; everything else (Kotlin redundancy, Markdown, Grazie proofreading) rides
  on the IDE's built-in defaults, which the headless inspector loads too — so the run reproduces
  the full Inspect Code result, not just the serialized entries.

### Gotchas

- **Slow** — minutes, because it boots a headless Studio. It's a pre-merge step, not a fast loop.
- **Gradle/IDE lock** — do **not** run while Android Studio has this project open, or while a
  `gradlew` task is running. Same build-lock deadlock documented in [Lesson 012](lessons_learned/012-camera-bound-screen-single-call-site.md).
- **Environment-gated** — if `inspect.bat` isn't present (a cloud/CI runner without Studio), the
  reviewer must state Engine 2 was **not run** rather than implying a pass. See Tier 3.
- **⚠️ An empty output dir is NOT a pass — verify with a probe.** On Studio **Quail 2026.1.1**
  (`AI-261.23567.138.2611.15503007`) the headless run completes in ~10 s, writes only
  `.descriptions.xml`, and reports zero problems — because it never loads the Gradle/Android
  module model (`inspect.bat` does not run a Gradle sync, and this project keeps its modules in
  external storage: `ExternalStorageConfigurationManager enabled="true"`, no `modules.xml`/`.iml`
  under `.idea/`). The log tell is `PerProjectIndexingQueue - Finished for [OpenLoop]. No files to
  index with loading content.` Confirmed 2026-08-08 by dropping in a throwaway `.kt` with an unused
  import, a redundant explicit type, a stray semicolon and three typos: **still zero result files.**
  Same shape as [Lesson 011](lessons_learned/011-16kb-uncompressed-native-libs.md)'s vacuous
  `zipalign` pass. **Before trusting a clean headless run, plant such a probe and confirm it is
  reported; if it isn't, run Engine 2 from the IDE instead (Analyze → Inspect Code), which uses the
  synced model.**

---

## Tier 3 — lightweight OSS fallback for environments without Android Studio ([Issue #21](https://github.com/stozo04/OpenLoop/issues/21))

When the reviewer runs somewhere without Android Studio (a cloud runner / CI), Engine 2 can't
run. Tier 3 is a fast, headless, **Node-based** approximation of Engine 2's high-value subset. It
**supplements** Tier 2 — it does not replace it (it has no equivalent of Grazie grammar).

> **Advisory by design.** Tier 3 findings are surfaced at **RECOMMENDATION** severity, not as a
> hard gate. None of these tools has a lint-style baseline, so Tier 3 is **scoped to a PR's
> changed Markdown files** rather than the whole repo (the existing docs carry ~600 legacy
> markdownlint hits). Caveat: file-level scoping means a *modified* doc surfaces its pre-existing
> issues too, not only the changed lines — read Tier 3 output as "worth a look," not "blocking."

### The tools (all run via `npx`, no committed `node_modules`)

| Tool | Config (committed) | Approximates (Engine 2 finding) |
|------|--------------------|---------------------------------|
| [`markdownlint-cli2`](https://github.com/DavidAnson/markdownlint-cli2) | `.markdownlint-cli2.jsonc` | Markdown table formatting, ordered-list numbering, list/heading/fence spacing |
| [`markdown-link-check`](https://github.com/tcort/markdown-link-check) | `.markdown-link-check.json` | "Unresolved file references" (validates **relative** links offline; HTTP is ignored — external-URL liveness is intentionally out of scope, it's flaky in CI) |
| [`cspell`](https://cspell.org) | `cspell.json` | Proofreading "typos" (`words` is the project dictionary of domain/tool proper-nouns) |

The configs are tuned to match what Inspect Code actually reports: `markdownlint` disables the
opinionated prose rules IntelliJ doesn't flag (`MD013` line-length, `MD060` table pipe-spacing,
`MD033` inline-HTML); `cspell` is seeded with `OpenLoop`, `CameraX`, `ExoPlayer`, `detekt`,
`Grazie`, etc. so real terms aren't flagged as typos.

### Running it

```bash
# Scope to the Markdown this PR changed (the intended use):
FILES=$(git diff --name-only --diff-filter=d main...HEAD -- '*.md')
npx --yes markdownlint-cli2 $FILES
npx --yes cspell --no-progress $FILES
for f in $FILES; do npx --yes markdown-link-check --config .markdown-link-check.json "$f"; done

# Whole-repo audit (noisy — expect the ~600 legacy markdownlint hits):
npx --yes markdownlint-cli2 "**/*.md"
```

> Grow `cspell.json`'s `words` list when it flags a legitimate term — **don't disable the check**.
> Same spirit as the lint baseline: keep the signal, don't silence it.

### Why detekt was deferred (not in this tier yet)

[Issue #21](https://github.com/stozo04/OpenLoop/issues/21) originally proposed **detekt** for the
Kotlin-redundancy class. DD finding: **stable detekt (1.23.x) does not support Kotlin 2.3+** —
only [detekt 2.0.0-alpha](https://detekt.dev/docs/introduction/compatibility/) does, and this
project is on Kotlin 2.4.10. Taking an *alpha* static-analysis dependency into the build for a
merge gate isn't worth the instability today, so detekt is deferred until detekt 2.0 is stable.
Until then the Kotlin-redundancy class stays covered by **Tier 2** (`inspect.bat`) when run
locally. Re-evaluate when detekt 2.0 ships stable; tracked in #21.

### Real findings on the first run (this tooling already paid off)

Running `markdown-link-check` across the changed docs immediately surfaced genuine **pre-existing
broken references on `main`** (not introduced by this work):

- `README.md` and `CLAUDE.md` once linked to a missing **`docs/android-16/README.md`** hub (path drift vs git). **Fixed in [#23](https://github.com/stozo04/OpenLoop/pull/23)**; the hub was later removed in the doc-layout cleanup — Android 16 policy now lives in `ANDROID_STANDARDS.md` §11 and Google's behavior-changes page.
- `README.md` linked to a **`LICENSE`** file that did not exist (the project states Apache 2.0).
  **Fixed in [#23](https://github.com/stozo04/OpenLoop/pull/23)** (added verbatim Apache 2.0 text).

### Doc layout gate — GitHub Actions (hard)

**`.github/workflows/doc-layout.yml`** runs on every pull request. It fails if the PR **adds** any
`*.md` file outside `docs/` (allowed exceptions: root `README.md`, `CLAUDE.md`). Policy:
[`docs/README.md`](README.md) § Enforcement.

### Hosting Tier 3 — GitHub Actions (active)

Tier 3 runs in CI via **`.github/workflows/static-analysis.yml`** (`pull_request` on `**/*.md`,
plus `workflow_dispatch` for manual runs). It uses `actions/checkout@v6` + `actions/setup-node@v6`
(Node 24-era majors), diffs the PR's changed Markdown against the base SHA, and runs the three
tools inside collapsible log groups. **Steps are non-blocking (`|| true`)** — findings surface in
the job log, they don't fail the PR (advisory, per the design above). To promote a tool to a hard
gate (e.g. fail on a newly-introduced dead link), drop its `|| true` in the workflow.

---

## How this plugs into the merge gate

1. `pr-reviewer` **Phase 3.5** runs **Engine 1 (Lint)** automatically and folds findings into the
   report at the mapped severity, with a new **"Static Analysis (Lint + IDE Inspect)"** row in the
   summary table.
2. The review's Verdict states whether **Engine 2 (IDE Inspect)** was run locally or skipped — its
   absence never reads as a pass.
3. The [README PR Merge Policy](../README.md#pr-merge-policy) lists both engines as merge
   requirements.
