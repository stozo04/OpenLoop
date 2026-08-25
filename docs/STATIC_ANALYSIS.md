# Static Analysis — Reproducing Android Studio's "Inspect Code" as a merge gate

This is OpenLoop's plan and runbook for running the same checks Android Studio's **Analyze →
Inspect Code** produces, headlessly, and folding them into the PR-merge gate alongside the
[`pr-reviewer`](../.claude/skills/pr-reviewer/SKILL.md) standards review.

Last verified: 2026-08-25 · AGP 9.3.2 · Gradle 9.7.1 · Android Studio at `C:\Program Files\Android\Android Studio`

> **The gate is one command:** `.\scripts\pre-pr-sweep.ps1` runs every engine and tool on this page to
> **zero** and writes `build/sweep-receipt.json`; a Claude Code hook refuses PR creation without it.
> Full flow: [`DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md). This page is the *why* and the per-tool detail.

---

## The key insight: "Inspect Code" is two engines

Android Studio's single "Inspect Code" action is really **two analysis engines stacked**, and
they differ enormously in how headless-runnable they are. OpenLoop treats them as two tiers.

| Engine                               | Catches (examples from a real Inspect Code run)                                                                                                                                                                                                                                                                        | Headless?                                                                                                           |
| ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| **1. Android Lint**                  | *Correctness*: Obsolete Gradle dependency, Newer library versions available, Target SDK not latest. *Performance*: `mipmap-anydpi-v26` unnecessary (`ObsoleteSdkInt`). *Usability*: image in density-independent drawable folder (`IconLocation`), monochrome icon not defined, launcher silhouette, duplicated icons. | ✅ **Yes** — `./gradlew :app:lintDebug`, no IDE needed                                                               |
| **2. IntelliJ inspections + Grazie** | Kotlin *redundant constructs*, Java *declaration redundancy*, *Markdown* table formatting / numbered lists / **unresolved file references**, the Markdown "Annotator" parse errors ("Expecting an element"), and *Proofreading* (grammar, typos, style).                                                               | ⚠️ **IDE-only** — needs `inspect.bat`; slow; not reproducible by lint or standalone OSS tools (esp. Grazie grammar) |

---

## Tier 1 — Android Lint (automated gate, runs on every review)

Lint is deterministic and CI-safe, so it is wired directly into the `pr-reviewer` skill
(Phase 3.5) and into the sweep, and is a **hard merge gate**: zero lint **errors and warnings**.
The sweep parses `lint-results-debug.xml` itself rather than flipping `abortOnError` /
`warningsAsErrors` — the reviewer skill still needs the build to succeed and emit a full report.
The version-freshness checks (`GradleDependency`, `NewerVersionAvailable`,
`AndroidGradlePluginVersion`) are **advisory**: their messages embed a moving upstream version, so
they self-invalidate on a schedule nobody here controls (see "Message drift" below).

### Configuration (already in `app/build.gradle.kts`)

```kotlin
android {
    lint {
        // XML + HTML reports are always generated on AGP 9.3+ (xmlReport/htmlReport are deprecated no-ops)
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

| Entries | Issue                                       | How it was cleared                                                                                                           |
| ------- | ------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| 2       | `MonochromeLauncherIcon`                    | Added a `<monochrome>` layer — themed icons on Android 13+ (a real product gap, not just a lint nag)                         |
| 2 + 1   | `IconLauncherShape`, `IconDuplicates`       | Deleted `mipmap-xxxhdpi/` — legacy pre-API-26 bitmaps, unreachable at minSdk 26                                              |
| 1       | `ObsoleteSdkInt`                            | `mipmap-anydpi-v26/` → `mipmap-anydpi/`                                                                                      |
| 1       | `IconLocation`                              | `onboarding_skater.jpg` deleted — it only ever rendered under Compose `@Preview`, and shipped 649 KB to do it (owner's call) |
| 1       | `UnusedAttribute`                           | Scoped `tools:ignore` **at the source** in `AndroidManifest.xml`, with the reason next to the attribute                      |
| 2 + 1   | `GradleDependency`, `NewerVersionAvailable` | Bumped activity-compose, datastore-preferences, kotlinx-coroutines-test to current stable                                    |

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

| Lint severity / category                                                                                           | Review severity         |
| ------------------------------------------------------------------------------------------------------------------ | ----------------------- |
| `Error` / `Fatal`                                                                                                  | **FAIL**                |
| `Warning` in Correctness / Security / Performance (`OldTargetApi`, `GradleDependency`, `NewerVersionAvailable`, …) | **WARNING**             |
| `Warning` in Usability / i18n / icons                                                                              | **RECOMMENDATION**      |
| `Hint` / `Informational` (e.g. `LintBaseline`)                                                                     | ignored — not a finding |

---

## Tier 2 — IDE inspections + proofreading (faithful; run from Android Studio, parsed by the sweep)

This is the **only** faithful reproduction of the Kotlin-redundancy, Markdown, and **proofreading**
findings — it is literally the engine Android Studio runs. It cannot be run headlessly on this
machine (see the gotcha below), so the flow is: run it **in the IDE**, export HTML, and let the
sweep turn the export into a pass/fail over tracked files.

### Running it

1. **Scope first.** Code → Inspect Code → *Custom scope* → **OpenLoop Tracked**. The scope is
   committed at `.idea/scopes/OpenLoop_Tracked.xml` (`.gitignore` un-ignores `.idea/scopes/` and
   `.idea/dictionaries/` for exactly this). It excludes `.claude/worktrees/` (git worktrees — full
   copies of the repo), build output, the gitignored DeepAR bundle, `docs/local/`, the swarm
   message-bus logs and every other file git does not track. A "whole project" run once produced
   **82,752 items in a 9 GB export, 98 % of them phantom** (Lesson 038).
2. **Export → HTML** into `build/inspect-export/` (gitignored). Don't put it at the repo root.
3. **Parse it:** `python scripts/inspect-report.py build/inspect-export/index.html` — or just run the
   sweep, which does this in gate 9. The parser streams the export, keeps only problems in files
   `git ls-files` knows, and exits non-zero on any *hard* finding. Advisory inspections (the
   version-freshness checks, and the Play Policy "Foreground Services Insights" whose justification
   lives in Play Console, not in code) are listed but never fail it. `--tsv` writes the full list.
4. **Typos** are dictionary-driven: `cspell.json` `words` is the single project vocabulary, and
   `python scripts/sync-ide-dictionary.py` derives `.idea/dictionaries/project.xml` from it
   (committed; the sweep fails if it is stale). Add a legitimate term there, fix a real typo in
   place. IntelliJ looks words up case-insensitively, so the XML holds them lowercased.
5. **Suppress at the source, with a reason**, when an inspection is wrong for this code — the
   manifest's `<!--suppress AndroidDomInspection -->` above the Photo Picker `ModuleDependencies`
   service, `@Suppress("SameReturnValue")` on a documented design seam, `@Suppress("UnstableApiUsage")`
   on the Gradle template's `RepositoriesMode`. Never a baseline, never disabling the inspection.

### The headless route is vacuous here — don't trust it

`inspect.bat` exists (`C:\Program Files\Android\Android Studio\bin\inspect.bat <project> <profile>
<out> -v2 -d <scope>`), but on Studio **Quail** with this project's external-storage module model it
completes in ~10 s, writes only `.descriptions.xml`, and reports **zero problems because it indexed
zero files** — the log tell is `PerProjectIndexingQueue - Finished for [OpenLoop]. No files to index`.
Confirmed 2026-08-08 by planting a throwaway `.kt` with an unused import, a redundant type, a stray
semicolon and three typos: still zero result files. Same shape as [Lesson 011](lessons_learned/011-16kb-uncompressed-native-libs.md)'s
vacuous `zipalign` pass. **An empty output dir is not a pass.** Until that changes, the IDE run above
is the only Engine 2 that counts; an agent without Studio passes `-SkipInspectCode`, the receipt
records it, and the PR description must say so.

### What the IDE flags that nothing else does (so you know what the export is for)

- **Grazie** grammar and dialect rules ("American English uses '-er' instead of '-re'", comma
  before a coordinating conjunction, "Consider splitting this 45-word sentence"). The repo writes
  **American English** in prose and comments; identifiers, log keys, string literals that match
  platform messages (`"Pending dequeue output buffer request cancelled"`) and verbatim quotes are
  never "corrected".
- Kotlin inspections the compiler is silent on: unused imports/symbols, "parameter always has the
  same value", redundant `requireNotNull`, `Long` overloads where a `Duration` exists, and so on.
- Markdown "Incorrect table formatting" (pipes not vertically aligned — measured in UTF-16 units),
  "Incorrectly numbered list item" (including a wrapped line that happens to start with `023)`),
  "Unresolved file references" (a link to a bare directory resolves to `''` — link to a file).
- `Mismatched image size` on `docs/index.html`: `width`/`height` attributes must be the image's
  intrinsic size; CSS does the display sizing.

---

## Tier 3 — the headless text gates (hard; part of the sweep and of CI) ([Issue #21](https://github.com/stozo04/OpenLoop/issues/21))

Tier 3 is the **Node/Python** subset of Engine 2 that runs anywhere: in the sweep (gates 6–8), and
in CI (`.github/workflows/static-analysis.yml`) as a backstop. It **supplements** Tier 2 — it has no
equivalent of Grazie grammar — but for the classes it covers it is the same bar: **zero, whole repo,
no baseline.** (It used to be advisory and scoped to changed files while the docs carried ~600
legacy hits; those were cleared on 2026-08-25 and the gate went hard.)

### The tools (all run via `npx` / `python`, no committed `node_modules`)

| Tool                                                                   | Config (committed)               | Approximates (Engine 2 finding)                                                                                                                                                                                                            |
| ---------------------------------------------------------------------- | -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| [`markdownlint-cli2`](https://github.com/DavidAnson/markdownlint-cli2) | `.markdownlint-cli2.jsonc`       | Ordered-list numbering, list/heading/fence spacing, fence languages, emphasis-as-heading                                                                                                                                                   |
| `scripts/md-table-align.py`                                            | —                                | "Incorrect table formatting" — pipes vertically aligned, **UTF-16 units like the IDE**. markdownlint's `MD060 aligned` measures display width instead (an emoji counts 2), so it disagrees on ✅/❌ rows and is off; `--fix` rewrites tables |
| [`markdown-link-check`](https://github.com/tcort/markdown-link-check)  | `.markdown-link-check.json`      | "Unresolved file references" (validates **relative** links offline; HTTP is ignored — external-URL liveness is intentionally out of scope, it's flaky in CI)                                                                               |
| [`cspell`](https://cspell.org)                                         | `cspell.json`                    | "Typo" — over **every** tracked text file (Markdown, Kotlin, XML, scripts, configs), because the IDE spell-checks comments and string literals too. `words` is the single project dictionary; the IDE XML is generated from it             |
| `scripts/sync-ide-dictionary.py --check`                               | `.idea/dictionaries/project.xml` | Keeps the IDE's project dictionary identical to `cspell.json`                                                                                                                                                                              |
| JSON validity (`python -c json.loads`)                                 | —                                | "Compliance with JSON standard"                                                                                                                                                                                                            |

`markdownlint` still disables the opinionated prose rules IntelliJ doesn't flag (`MD013`
line-length, `MD033` inline-HTML, `MD041` first-line-heading). `cspell` stays `en,en-GB` so test
names and identifiers written in British English are not typos — dialect drift in *prose* is a
Grazie (Tier 2) finding, and the prose is American.

### Running it

```powershell
.\scripts\pre-pr-sweep.ps1 -DocsOnly          # gates 6–9 only (docs-only branches; the receipt records it)
```

or by hand, whole repo:

```bash
npx --yes markdownlint-cli2 $(git ls-files '*.md')
python scripts/md-table-align.py            # add --fix to rewrite
for f in $(git ls-files '*.md'); do npx --yes markdown-link-check --config .markdown-link-check.json -q "$f"; done
git ls-files '*.md' '*.kt' '*.kts' '*.xml' '*.yml' '*.ps1' '*.py' '*.mjs' '*.json' '*.html' | npx --yes cspell --no-progress --file-list stdin
python scripts/sync-ide-dictionary.py --check
```

> Grow `cspell.json`'s `words` list when it flags a legitimate term, then re-run
> `python scripts/sync-ide-dictionary.py` — **don't disable the check**. Same spirit as the lint
> baseline: keep the signal, don't silence it.

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

### Hosting Tier 3 — GitHub Actions (hard)

Tier 3 runs in CI via **`.github/workflows/static-analysis.yml`** on every pull request (plus
`workflow_dispatch`): `actions/checkout@v6`, `setup-node@v6`, `setup-python@v5`, then the same six
checks as sweep gates 6–8 over the **whole tracked tree**. Every step is a **hard** gate — the tree
is at zero, so a red step is something the PR introduced. The Gradle half of the sweep (build,
lint, tests) is deliberately not in CI yet: it cannot be tried without pushing, and the receipt hook
already makes it a precondition of every PR. Adding it is a follow-up, not a gap in the gate.

---

## How this plugs into the merge gate

1. **Before the PR exists:** `scripts/pre-pr-sweep.ps1` must be green on the final commit — the
   `PreToolUse` hook in `.claude/settings.json` (`scripts/hooks/require-sweep.mjs`) refuses
   `gh pr create` / `create_pull_request` without `build/sweep-receipt.json` for `HEAD`.
2. `pr-reviewer` **Phase 3.5** runs **Engine 1 (Lint)** automatically and folds findings into the
   report at the mapped severity, with a **"Static Analysis (Lint + IDE Inspect)"** row in the
   summary table.
3. The review's Verdict states whether **Engine 2 (IDE Inspect)** was parsed from an export or
   skipped (the receipt's `inspectCode` field says which) — its absence never reads as a pass.
4. CI's text gates re-run Tier 3 as a backstop.
5. The [README PR Merge Policy](../README.md#pr-merge-policy) lists all of it as merge requirements.
