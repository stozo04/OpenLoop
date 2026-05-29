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
  `gradlew` task is running. Same build-lock deadlock documented in Lesson 012's hand-off notes
  (`docs/lessons_learned/012-camera-bound-screen-single-call-site.md`, lands on `main` with the
  slice-01 PR — referenced as a path, not a link, so this doc stays valid before that merges).
- **Environment-gated** — if `inspect.bat` isn't present (a cloud/CI runner without Studio), the
  reviewer must state Engine 2 was **not run** rather than implying a pass. See Tier 3.

---

## Tier 3 — lightweight OSS fallback for environments without Android Studio ([Issue #21](https://github.com/stozo04/OpenRang/issues/21))

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
`MD033` inline-HTML); `cspell` is seeded with `OpenRang`, `CameraX`, `ExoPlayer`, `detekt`,
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

[Issue #21](https://github.com/stozo04/OpenRang/issues/21) originally proposed **detekt** for the
Kotlin-redundancy class. DD finding: **stable detekt (1.23.x) does not support Kotlin 2.3.x** —
only [detekt 2.0.0-alpha](https://detekt.dev/docs/introduction/compatibility/) does, and this
project is on Kotlin 2.3.21. Taking an *alpha* static-analysis dependency into the build for a
merge gate isn't worth the instability today, so detekt is deferred until detekt 2.0 is stable.
Until then the Kotlin-redundancy class stays covered by **Tier 2** (`inspect.bat`) when run
locally. Re-evaluate when detekt 2.0 ships stable; tracked in #21.

### Real findings on the first run (this tooling already paid off)

Running `markdown-link-check` across the changed docs immediately surfaced genuine **pre-existing
broken references on `main`** (not introduced by this work):

- `README.md` and `CLAUDE.md` link to **`docs/android-16/README.md`**, which exists on no branch.
- `README.md` links to a **`LICENSE`** file that does not exist in the repo (the project states
  Apache 2.0).

These are out of scope for the tooling PR and are surfaced for follow-up rather than fixed here.

### Hosting Tier 3 — decision for the owner

Tier 3 is built and runnable locally now. The open decision (**@stozo04's call**) is whether to
also run it automatically on PRs via **GitHub Actions** (no `.github/workflows/` exists yet). A
ready-to-use workflow is below — it is **not yet committed** because (a) activating CI is an
outward-facing recurring automation and (b) a GitHub Actions run can't be verified green from a
local session. Drop it into `.github/workflows/static-analysis.yml` and validate on a test PR to
activate.

```yaml
name: Static analysis (Tier 3)
on:
  pull_request:
    paths: ['**/*.md']
permissions:
  contents: read
jobs:
  markdown:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: actions/setup-node@v4
        with: { node-version: '22' }
      - name: Tier 3 on changed Markdown
        run: |
          FILES=$(git diff --name-only --diff-filter=d origin/${{ github.base_ref }}...HEAD -- '*.md')
          [ -z "$FILES" ] && { echo "No changed Markdown."; exit 0; }
          echo "Checking: $FILES"
          npx --yes markdownlint-cli2 $FILES || true        # advisory
          npx --yes cspell --no-progress $FILES || true     # advisory
          for f in $FILES; do npx --yes markdown-link-check --config .markdown-link-check.json "$f" || true; done
```

---

## How this plugs into the merge gate

1. `pr-reviewer` **Phase 3.5** runs **Engine 1 (Lint)** automatically and folds findings into the
   report at the mapped severity, with a new **"Static Analysis (Lint + IDE Inspect)"** row in the
   summary table.
2. The review's Verdict states whether **Engine 2 (IDE Inspect)** was run locally or skipped — its
   absence never reads as a pass.
3. The [README PR Merge Policy](../README.md#pr-merge-policy) lists both engines as merge
   requirements.
