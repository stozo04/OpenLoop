# Static Analysis — Reproducing Android Studio's "Inspect Code" as a merge gate

This is OpenRang's plan and runbook for running the same checks Android Studio's **Analyze →
Inspect Code** produces, headlessly, and folding them into the PR-merge gate alongside the
[`pr-reviewer`](../.claude/skills/pr-reviewer/SKILL.md) standards review.

Last verified: 2026-05-28 · AGP 8.13.2 · Android Studio at `C:\Program Files\Android\Android Studio`

---

## The key insight: "Inspect Code" is two engines

Android Studio's single "Inspect Code" action is really **two analysis engines stacked**, and
they differ enormously in how headless-runnable they are. OpenRang treats them as two tiers.

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
        baseline = file("lint-baseline.xml")
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
  clean run prints `BUILD SUCCESSFUL` with exit `0`, and the XML contains only the informational
  `id="LintBaseline" severity="Hint"` entry (which reports how many pre-existing warnings were
  filtered). Anything else under `<issue>` is a finding introduced by your branch.

### The baseline

The repo carried **~294 pre-existing inspection items** when this gate was added. Without a
baseline, every PR would re-report all of them and bury the real signal. `lint-baseline.xml`
(committed, at `app/lint-baseline.xml`) snapshots the lint-detectable subset (19 issues:
`GradleDependency`, `IconLocation`, `NewerVersionAvailable`, `MonochromeLauncherIcon`,
`IconLauncherShape`, `ObsoleteSdkInt`, `IconDuplicates`, `AndroidGradlePluginVersion`,
`UnusedAttribute`) so lint reports **only newly-introduced** issues.

> ⚠️ **Regenerate the baseline only deliberately.** Deleting `app/lint-baseline.xml` and
> re-running lint regenerates it — which *silently swallows every issue currently in the tree*,
> including ones a PR just introduced. Treat a baseline change like a code change: it needs a
> reason and a review. To regenerate intentionally: delete the file, run `:app:lintDebug` once
> (it creates the baseline and aborts — this is normal AGP behavior), then run it again to get a
> green build. Ideally do this only when burning down the pre-existing items, never to hide new ones.

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
IDE instance), so it is **not** part of the automated skill run. Instead the author runs it
locally before opening/merging a PR, and the merge policy requires it to be clean.

### Running it

```powershell
& "C:\Program Files\Android\Android Studio\bin\inspect.bat" `
  "C:\Users\gates\Personal\OpenRang" `
  "C:\Users\gates\Personal\OpenRang\.idea\inspectionProfiles\Project_Default.xml" `
  "C:\Users\gates\Personal\OpenRang\build\inspection-results" `
  -v2 -d "C:\Users\gates\Personal\OpenRang"
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
  `gradlew` task is running. Same build-lock deadlock documented in
  [Lesson 012](lessons_learned/012-camera-bound-screen-single-call-site.md)'s hand-off notes.
- **Environment-gated** — if `inspect.bat` isn't present (a cloud/CI runner without Studio), the
  reviewer must state Engine 2 was **not run** rather than implying a pass. See Tier 3.

---

## Tier 3 — lightweight CI fallback (not yet built — tracked in [Issue #21](https://github.com/stozo04/OpenRang/issues/21))

For environments where Android Studio isn't installed (cloud reviewers/CI), a fast OSS
approximation of Engine 2's high-value subset: **detekt** (Kotlin redundancy/style),
**markdownlint-cli** (table formatting, numbered lists), a **link-checker** (Markdown unresolved
file references), **cspell/codespell** (typos). It misses Grazie grammar entirely, so it
*supplements* rather than replaces Tier 2. Deferred and tracked as a follow-up issue.

---

## How this plugs into the merge gate

1. `pr-reviewer` **Phase 3.5** runs **Engine 1 (Lint)** automatically and folds findings into the
   report at the mapped severity, with a new **"Static Analysis (Lint + IDE Inspect)"** row in the
   summary table.
2. The review's Verdict states whether **Engine 2 (IDE Inspect)** was run locally or skipped — its
   absence never reads as a pass.
3. The [README PR Merge Policy](../README.md#pr-merge-policy) lists both engines as merge
   requirements.
