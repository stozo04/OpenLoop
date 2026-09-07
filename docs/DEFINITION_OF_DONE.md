# Definition of Done — the "Ready for PR" verification gate

**A change is not "done" because it compiles. It is done when it has been built, tested, *run*, and honestly reported.** This is the gate every non-trivial change must clear before it is called done or opened as a PR. It applies to humans and to Claude Code agents working in this repo.

The guiding principle: **don't trust "it compiles" — prove it.** Prove it builds clean, prove the tests pass, prove the app actually *runs*, and be explicit about what you could not verify.

---

## Production zero-error rule (non-negotiable)

**OpenLoop is live in Production and reachable by billions of users. Any error the agent encounters while working — a failing test, a compile error, a lint error, a crash — MUST be fixed before a PR is created. No exceptions, even for pre-existing failures the agent did not introduce.**

- "Not my change" is **not** a reason to leave a red build. If you touch a module and its tests don't compile or don't pass, you fix them as part of the work. If the fix is genuinely out of scope, you **stop and flag it to the owner and get explicit direction** — you do not open a PR on top of a known-broken baseline.
- A PR must be opened only from a **fully green** state: clean debug + release build, **0 test failures**, **0 lint errors, 0 lint warnings, 0 compiler warnings, 0 Markdown/spelling/inspection findings**. A known failing test in the branch is a release blocker, full stop.
- **The pre-PR sweep is the gate, and it is mechanical.** `.\scripts\pre-pr-sweep.ps1` runs every check below and writes `build/sweep-receipt.json` only when all of them are green. The receipt records whether `-Clean` ran and the duration of every gate. A Claude Code `PreToolUse` hook (`scripts/hooks/require-sweep.mjs`, wired in `.claude/settings.json`) refuses `gh pr create` and the GitHub `create_pull_request` tool unless that receipt exists **for the current `HEAD` on a clean tree** — so the sweep is, by construction, the last thing that runs after the final commit. Humans run the same script; there is no other route to a PR.
- If you discover the breakage was already on `main`, that makes it **more** urgent, not less — a broken gate on `main` means the safety net is down for every future change. Repair it (or escalate) immediately; never build on top of it.
- Capture the green proof (build verdict + exit 0 + test counts, per the gate below) in the PR.

## Installed-app proof

The installed-APK onboarding check is part of this gate:

```bash
python scripts/run-verification-loops.py --all
```

The runner executes every shipped loop, including onboarding, lenses, photo mode, recording, and
reverse preview after a nonzero trim. Onboarding alone resets its DataStore; each loop retains its
process restart and owned cleanup. Identical installed APK bytes avoid a reinstall, never a test.
The sweep runs these loops before instrumented tests, with one controller per emulator.
`-SkipConnected` skips both stages and must be reported. On Windows, use `python` or `py -3`.

## Choose verification scope

Trace the changed behavior through its callers, shared state, resources, and build dependencies.
File count does not establish risk. A one-line change in the ViewModel, manifest, codec pipeline,
shared verifier, or build configuration can affect many features.

| Stage                    | Required scope                                                                                                                                                                                                                                                              |
| ------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Local iteration          | Run the affected existing JVM classes, instrumented classes, and installed-app loops. Include downstream callers. List excluded checks; this is not a PR receipt.                                                                                                           |
| PR validation            | Once the diff and documentation are ready, commit and run one full sweep. It builds debug, release, and the test APK, checks all JVM results, executes all installed-app loops and instrumented tests, and runs the static gates. All-Markdown branches retain `-DocsOnly`. |
| Release validation       | Run the full sweep with `-Clean -RerunTests`, plus the API-34 and other applicable device lanes. Build the shipping bundle normally, without the verification property.                                                                                                     |
| Unknown or shared change | Use the full sweep and applicable device lanes. Do not infer a narrow scope from filenames or a small diff.                                                                                                                                                                 |

For example, trim-handle math has a focused JVM check; its capture and editor callers need device
verification when their behavior changes:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*TrimHandleMathTest'
.\gradlew.bat :app:assembleDebug
python scripts/run-verification-loops.py --loops record_clip reverse_preview_trim
.\scripts\pre-pr-sweep.ps1 -SkipInspectCode
.\scripts\pre-pr-sweep.ps1 -Clean -RerunTests -SkipInspectCode
```

The last two commands are alternatives for ordinary PR and forced full validation.
Use `-SkipInspectCode` only when an IDE export is unavailable. For a targeted instrumented run,
use Gradle's `-Pandroid.testInstrumentationRunnerArguments.class=<fully-qualified-class>`.
The full sweep supplies no test filter and rejects partial loop receipts. `--changed` remains a
compatibility alias for all loops; it does not infer dependency coverage.

Build the current APK before a standalone loop. Matching the installed hash proves which APK ran,
not whether someone built it from the latest source. The runner records APK and verifier hashes,
selection, omissions, durations, and a separate evidence directory per run.

The sweep preserves its previous logs and receipt under `build/sweep-history/`. Gradle profiles
under `build/reports/profile/` separate task costs. Receipts distinguish `EXECUTED`,
`FROM-CACHE`, and `UP-TO-DATE` JVM results and report skips. Reused results are not newly executed
coverage. `-RerunTests` forces just the JVM test task to execute; it does not rebuild every task.

The sweep passes `-PopenloopVerification=true`. This disables Crashlytics mapping uploads for
verification release APKs, keeping the mapping ID stable so unchanged resources and R8 can be
reused. R8, resource shrinking, Firebase initialization, and the missing-config release guard remain
enabled. These APKs are verification artifacts; their crash mappings are not published.
The property rejects `bundleRelease`. Normal release builds omit it and retain mapping uploads.

Do not run a separate full loop pass or reinstall solely for a screenshot before or after a green
sweep. Its loop evidence already contains launch proof, UI dumps, and screenshots. Reuse those
artifacts for the reviewed APK. After a failure, run the affected check while fixing it, then run
the final sweep once after the final commit. Review can reuse that exact commit's receipt.

---

## Markdown rules (non-negotiable)

Every PR that adds or edits a `.md` file clears all five of these **before the commit**, not after CI says no.
Both have failed repeatedly in CI (PR #161 hit both at once), which is why they are their own gate.

### M1. New Markdown lives under `docs/`

**A brand-new `.md` file goes under `docs/`.** The only exceptions are the allowlist in
[`docs/README.md` § Enforcement](README.md#enforcement) — root `README.md` / `CLAUDE.md` / `AGENTS.md`
and the agent-harness paths (`.claude/`, `.cursor/`, `.codex/`, `swarm/`). That list is the
single source of truth; it is enforced by [`.github/workflows/doc-layout.yml`](../.github/workflows/doc-layout.yml)
against `git diff --diff-filter=A`.

- **Do not widen the allowlist to make your file fit.** Move the file into `docs/`. Widening the gate is
  an owner decision, requested explicitly and justified in the PR — never a workaround for a red check.
  (The one widening so far: root `AGENTS.md`, because each LLM harness auto-discovers only *its* filename
  at the repo root. See M3.)
- Editing an existing root `.md` is fine (the gate only sees *added* files) — but a doc that would be
  new today belongs in `docs/` today.
- Check yourself before committing:

  ```powershell
  git diff --name-only --diff-filter=A origin/main...HEAD -- '*.md'
  ```

### M2. Markdown is linted to zero locally, before the commit

**Never let CI be the first thing that runs markdownlint.** The text gates (gate 3 below) are not a
CI-only formality — run them locally on every doc change and fix to zero:

```powershell
npx markdownlint-cli2 --fix "**/*.md" "#node_modules"   # MD022/MD032/MD047 etc. are auto-fixable
.\scripts\pre-pr-sweep.ps1 -DocsOnly                    # the receipt-producing gate for an all-.md PR
```

`-DocsOnly` is valid **only when every changed file is `.md`** (hook-enforced); one `.yml` or `.kt` in the
diff means the full sweep. The three failures that keep recurring: **MD047** (file must end with exactly
one newline), **MD022** (blank line above *and below* every heading), **MD032** (blank line around lists).

### M3. Agent instructions live in one shared copy

The owner drives this repo with several LLMs, so the instructions exist **once** (owner instruction,
2026-08-30): [`docs/OPERATING_INSTRUCTIONS.md`](OPERATING_INSTRUCTIONS.md) (how to work here) and
[`docs/OPENLOOP_INSTRUCTIONS.md`](OPENLOOP_INSTRUCTIONS.md) (what OpenLoop is). Root `CLAUDE.md` and
`AGENTS.md` are byte-identical two-line pointers at those files and carry **no content of their own** —
they exist only because each harness auto-discovers its own filename at the repo root.

- **Never fork a per-tool copy** of an instruction file, and never paste content back into a root pointer.
  Edit the `docs/` file; every LLM picks the change up.
- Adding a harness that reads a different root filename? Add another two-line pointer *and* the
  allowlist entry in `doc-layout.yml` + `docs/README.md` § Enforcement, in the same PR.

### M4. Moved or renamed something? Grep the whole repo for stale references

**Every move, rename, or delete leaves references behind, and they are not all links.** `markdown-link-check`
only catches broken *relative link targets* — it does not catch a wrong heading anchor, a path in backticks,
a path inside a `.yml` / `.kt` / `.ps1`, or prose that still describes the old layout. Those go stale
silently and mislead the next reader (human or agent) for months.

Before committing a move, grep for **both the old and the new name** across the whole tree:

```powershell
git grep -n "OldName"                       # every mention, not just Markdown links
git grep -n "old/path/to/file.md"           # the path form too
```

Then fix, in this order:

1. **Functional breaks first** — link targets and `#heading-anchors` that no longer resolve.
   (Moving a file also rebases every *relative* link **inside** it: `docs/x.md` → `x.md` one level down.)
2. **Statements that are now false** — allowlists, folder maps, file counts, "this file" self-references.
3. **Prose pointers** that still resolve through an indirection can stay; a pointer that now names the
   wrong thing cannot.

Leave alone: historical records that were true when written (a PRD's "per X I searched…", a lessons-learned
entry, a changelog). Those describe the past, not the current layout.

### M5. Harness skill packages are identical across `.claude/`, `.cursor/`, `.codex/`

The same rule as M3, one level down. The owner runs three LLM providers on this repo routinely and
each harness auto-discovers skills **only** under its own directory, so the skills cannot live in one
shared copy the way the instruction files do — they live as three copies of one thing, and those
copies must never diverge (owner instruction, 2026-08-30). A skill fixed for one LLM that stays broken
for the other two is worse than not fixing it: nothing on disk says which copy is current.

**If an LLM changes its own skills, the other two get the same bytes in the same commit.**

**Whatever LLM you are, run the check the moment you finish touching a skills tree** — before the
commit, not at sweep time. It is read-only and takes under a second, and the `harness-sync` skill
(present in all three harnesses) drives it:

```powershell
python scripts/sync-harness-skills.py         # --check is the default: exit 1 on drift
python scripts/sync-harness-skills.py --fix   # propagate the change to the other two
```

**Aligning carries the change outward; it never deletes it to match the untouched two.** Both
directions make the trees agree and turn the gate green, so the wrong one destroys the edit
silently — the reason the script takes the direction from git rather than from whoever ran it.
Bare `--fix` propagates from the one tree git says changed, and stops if none or several did
(edited separately means no copy is authoritative — reconcile by hand, then run it). `--from`
overrides that, but is refused when it contradicts git, naming the tree whose work would have
been lost; `--force` is the deliberate discard and needs a reason in the PR.

Enforced as sweep gate **6d** and as a CI step in
[`static-analysis.yml`](../.github/workflows/static-analysis.yml); both are hard. The mechanism
has its own free, offline test — `python scripts/test-sync-harness-skills.py` — run it after
changing the script.

- **One difference is allowed: a skill's pointer at its own tree.** A recipe that says "run
  `.claude/skills/verify-openloop/helpers/onboarding_loop.py`" has to name a different directory in
  each copy, or two of the three send their LLM to a path it cannot read (owner instruction,
  2026-08-31). `.claude/skills`, `.cursor/skills` and `.codex/skills` are therefore compared as one
  token and `--fix` rewrites it per destination. Every other byte still has to match, and each copy
  must point at **itself** — `.cursor`'s copy naming `.codex/skills` is drift. Paths outside
  `<harness>/skills` (`~/.cursor/mcp.json`, `.claude/commands/`) are genuinely one harness's and
  stay literal in all three.
- **Scope is `skills/**` only.** `settings.json` is harness-specific by design (Claude's carries
  marketplaces, plugins and hooks; the other two a plugin stub) and `.claude/commands/` has no
  counterpart — none of that is compared.
- **Adding a fourth harness?** Create `.<name>/skills/`, add it to `HARNESSES` in the script, and add
  the `doc-layout.yml` + [`docs/README.md` § Enforcement](README.md#enforcement) allowlist entries — in
  the same PR, per M1.

## The gate (run top to bottom)

> **One command runs steps 1–4 and the text gates and enforces zero across the board:**
>
> ```powershell
> .\scripts\pre-pr-sweep.ps1                         # full sweep — emulator/device attached, Inspect Code export present
> .\scripts\pre-pr-sweep.ps1 -Clean                  # cold build after build-tool/dependency changes or suspected stale outputs
> .\scripts\pre-pr-sweep.ps1 -SkipConnected -SkipInspectCode   # what an agent without Studio/emulator can run; the PR must say so
> ```
>
> It reports every gate (it never stops at the first red), logs to `build/sweep.log`, and writes
> `build/sweep-receipt.json` on green. Fast text gates run first, build + Lint + JVM tests share one
> Gradle invocation, then gate **5b** (the onboarding loop) runs before instrumented tests so the two
> never contend for one emulator. The steps below are what it runs, kept here so a human can reproduce
> any one of them by hand.

### 0. Baseline — before you change anything

Capture a green check of the starting behavior before changing it. An existing receipt for the exact
starting commit can supply the baseline when its scope covers the change; record its skips and cache
status. Otherwise run the affected check. Do not prepend a separate clean build to every task:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # Android Studio's bundled JDK
.\gradlew.bat assembleDebug --console=plain
```

### 1. Build — debug AND release, genuinely green

Release matters: it runs R8/shrinking and resource crunching that debug skips, and catches things debug never will (it's how we found mislabeled JPEG drawables and R8 issues).

```powershell
.\gradlew.bat assembleDebug assembleRelease -PopenloopVerification=true --console=plain
$buildExit = $LASTEXITCODE
```

See **["Genuinely green"](#what-genuinely-green-means)** below — a finished command is not the same as a passed build.

### 2. Requirement / artifact checks

Verify the things a build alone doesn't prove. Example for this app — **16 KB native-lib alignment** (see [Lesson 011](lessons_learned/011-16kb-uncompressed-native-libs.md)):

```text
<sdk>/build-tools/<ver>/zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release-unsigned.apk
# .so lines must read "(OK)" at 16384-multiple offsets — NOT "(OK - compressed)"
```

**Release bumps (`chore/release-<version>`) carry one more check:** the Play technical quality
checklist in [`play-store/README.md`](play-store/README.md#technical-quality-requirements-enforced-feb--apr-2027)
— Android vitals *Memory* rows under Play's thresholds and the live bundle's *App optimization* still
*High* — with the numbers pasted into the PR. Play enforces those thresholds from Feb 2027 with
reduced visibility and publishing capabilities; a red row is a release blocker, same as a failing test.

### 3. Static analysis — code inspection (Android Studio "Inspect Code", headless)

Reproduce both Inspect Code engines and clear them. Full design + severity rules: **[`docs/STATIC_ANALYSIS.md`](STATIC_ANALYSIS.md)**.

```powershell
.\gradlew.bat :app:lintDebug --console=plain        # Engine 1 — Android Lint (the automated gate)
```

There is no lint baseline, so the report must show **zero `severity="Error"` entries and zero `severity="Warning"` entries** (the sweep parses the XML; only the version-freshness checks `GradleDependency` / `NewerVersionAvailable` / `AndroidGradlePluginVersion` are advisory). Then **Engine 2** (IDE inspections + proofreading): in Android Studio run **Code → Inspect Code** with the custom scope **OpenLoop Tracked**, export the result as HTML into `build/inspect-export/`, and let the sweep parse it (`python scripts/inspect-report.py build/inspect-export/index.html` — zero hard findings in tracked files). The headless `inspect.bat` is vacuous on this machine (`STATIC_ANALYSIS.md`), so the IDE run is the real one. **Do not introduce a `lint-baseline.xml` to silence findings** — a generated baseline swallows every issue currently in the tree, including ones the PR just added. Fix it, or suppress it at the source with a stated reason.

The text gates run alongside with no baseline: `markdownlint-cli2`, `python scripts/md-table-align.py`, `cspell` over every tracked text file (legit terms go into `cspell.json` `words`; `python scripts/sync-ide-dictionary.py` keeps the IDE dictionary identical), and JSON validity. `markdown-link-check` checks changed Markdown only; any tracked delete or rename switches it to all Markdown so unchanged inbound links are still protected.

### 4. Automated tests — unit and instrumented

```powershell
.\gradlew.bat testDebugUnitTest --console=plain                 # JVM, no device
$env:ANDROID_SERIAL = "emulator-XXXX"                            # pin the device if more than one is attached
.\gradlew.bat connectedDebugAndroidTest --console=plain         # needs a booted emulator/device
```

Read the actual counts (`tests=".." failures=".." errors=".."` in `app/build/.../*-results/`); confirm **0 failures**, not just `BUILD SUCCESSFUL`.

### 5. Run it for real — boot, install, launch, screenshot, and onboarding proof

The full sweep's installed-app loops satisfy this step. Read their UI and logcat evidence and attach
a screenshot, such as onboarding's `returning.png`, from the receipt's evidence directory. For
standalone manual work, boot an emulator, build the current APK, and run
`python scripts/run-verification-loops.py --all`.

**Which APK:** debug is the default. When the change adds or bumps a dependency that ships native code, JNI, reflection, or a logging framework, run the **release** APK too and drive the code path that dependency serves — `assembleRelease` proves R8 compiled, not what it removed or renamed, and the first hand-tracking release build crashed on a lens tap that debug handled fine (Lesson 040).

```text
EMU=<sdk>/emulator/emulator.exe ; ADB=<sdk>/platform-tools/adb.exe
"$EMU" -avd <name> -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect &
"$ADB" wait-for-device
# poll until: getprop sys.boot_completed == 1
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" logcat -c
"$ADB" shell am start -W -n io.github.stozo04.openloop/.MainActivity      # check Status: ok, no Error
"$ADB" exec-out screencap -p > proof.png                        # attach to the PR
"$ADB" logcat -d | grep -iE "FATAL|AndroidRuntime"              # confirm no crash
```

> AGP uninstalls the app after connected tests. Reinstall only if continuing device work afterward;
> the earlier loop screenshots remain valid evidence for the tested APK. Use the onboarding loop's
> owned reset when testing first-run state.

### 6. Be honest about what you could NOT verify

State the coverage gaps plainly and hand off a manual QA checklist. Camera capture (simulated on emulators), specific-API-level runtime behavior, and large-screen (>=600dp) layout often need a real device or a specific emulator + a human. **Never claim success for something you didn't actually exercise** (Lesson 007's spirit).

### 7. Attach the proof to the PR

Put the screenshot(s) from step 5 in the PR description, alongside the build/test results. Visual proof + green build + test counts = a reviewable "done."

---

## What "genuinely green" means

A command finishing is **not** a passed build. Confirm all three:

1. The verdict line says **`BUILD SUCCESSFUL`** (not `BUILD FAILED`).
2. Gradle's **exit code is `0`** — `echo $LASTEXITCODE` (PowerShell) / `echo $?` (bash), captured *right after* gradlew.
3. **Zero `e:` lines** (Kotlin compile errors) **and zero `w:` lines.** Kotlin warnings are build failures (`allWarningsAsErrors = true` in `app/build.gradle.kts`); a `w:` from a build script (a deprecated DSL) is caught by the sweep. `Unable to strip ... .so` notes are benign.

> **Gotcha:** piping Gradle through `| tail` (or any pipe) gives you the *pipe's* exit code, not Gradle's — a failed build can look like it "passed." Read the `BUILD SUCCESSFUL`/`BUILD FAILED` line itself. (This is also in the README; it bit us once.)

---

## Environment notes (this machine)

- **Java:** Gradle needs a JDK. Use Android Studio's bundled JBR: `JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`. There is no system `java` on PATH here.
- **Wrapper:** `.\gradlew.bat` on Windows (`./gradlew` on macOS/Linux).
- **SDK:** `C:\Users\gates\AppData\Local\Android\Sdk` (`build-tools/<ver>/zipalign.exe`, `platform-tools/adb.exe`, `emulator/emulator.exe`).
- **Multiple devices:** set `$env:ANDROID_SERIAL` (or `adb -s <serial>`) so commands aren't ambiguous; clear ghost `offline` devices with `adb kill-server; adb start-server`.

---

## Copy-paste checklist for a PR

```text
- [ ] Production zero-error rule honored: NO known failing test / compile / lint error left behind (pre-existing included), or escalated to the owner
- [ ] M1 — every NEW `.md` in this PR is under `docs/` or the `docs/README.md` § Enforcement allowlist (checked with `git diff --diff-filter=A`); the doc-layout allowlist was NOT widened to fit a file
- [ ] M2 — markdownlint run locally to 0 BEFORE the commit (not left for CI); MD047/MD022/MD032 clear
- [ ] M3 — instruction changes went into the shared `docs/OPERATING_INSTRUCTIONS.md` / `docs/OPENLOOP_INSTRUCTIONS.md`; root `CLAUDE.md` / `AGENTS.md` are still content-free pointers, still byte-identical
- [ ] M4 — anything moved/renamed/deleted was `git grep`-ed repo-wide under BOTH names; broken anchors, false statements (allowlists, folder maps, counts, "this file") and wrong pointers fixed — link-check alone is not this check
- [ ] Starting behavior verified; baseline scope, cache status, and skips recorded
- [ ] assembleDebug + assembleRelease: BUILD SUCCESSFUL, exit 0, zero e:; `-Clean` used after build-tool/dependency changes or suspected stale outputs
- [ ] Requirement checks pass (e.g. zipalign -c -P 16 shows real (OK))
- [ ] Tracked-file hygiene + Gitleaks pass: no gitignored/generated files or secrets (API keys, tokens, passwords, signing material, or other credentials) are committed
- [ ] Release bump only: Play technical quality check done (vitals Memory rows under threshold, bundle App optimization High) — numbers pasted here
- [ ] `.\scripts\pre-pr-sweep.ps1` GREEN on the final commit: build 0 e:/0 w:, zipalign, Lint 0/0, tests 0 failures, Markdown + tables + links + cspell + JSON all zero, onboarding loop PASS (or SKIPPED with no emulator — not done). Receipt: build/sweep-receipt.json
- [ ] Inspect Code (Engine 2) export parsed to 0 hard findings — or the PR says it was SKIPPED and why
- [ ] Fenced code blocks compile as what they claim to be: the IDE parses a ```kotlin body as real
      Kotlin, so a `…` placeholder is a parse error, not a hint. Use real values, or `TODO()`, or
      put the elision inside a comment. Six of 1.0.50's eight Inspect Code errors were one such
      ellipsis ([Lesson 010](lessons_learned/010-markdown-code-fences-are-inspected.md))
- [ ] No `xmlns:android` outside `app/src/*/res/` (AndroidManifest excepted): an XML file the app
      never packages cannot resolve that schema in any IDE, and reports `URI is not registered`
      on every open. Generator input carries plain attribute names. Checked by sweep gate 8c
- [ ] Generated art regenerates byte-identically: a change to `swarm/art/*` or
      `swarm/tools/render_lens_art.py` means re-running `python swarm/tools/render_lens_art.py`
      (needs `numpy`, `Pillow`, `scipy`) and confirming `git status` on `drawable-nodpi/` is clean,
      or committing the regenerated WebP. The provenance claim in PRD §16 is only true if this runs
- [ ] Proofreading findings were accepted ONE AT A TIME, never bulk-applied: LanguageTool does not
      know this domain. A single bulk pass in 1.0.50 produced `We must be NOT downscale here`,
      `revert any edit made sense`, and `a narrowband along the silhouette edge` — three comments
      whose technical meaning was destroyed by a grammar fix. Read every suggestion against the code
- [ ] Play-facing docs aligned (owner rule, 2026-08-24): permission/telemetry/storage changes → data-safety.md + privacy policy (md + html, new effective date); lens/feature changes → store-listing.md (+ docs/index.html, root README.md)
- [ ] Agent memory aligned (owner rule, 2026-08-24, Claude sessions): the project memory (`MEMORY.md` index + entries) reflects what this PR changed — new durable facts captured, entries this PR made stale corrected or deleted. Memory is never left stale.
- [ ] Root `README.md` aligned (owner rule, 2026-08-24): the GitHub-facing README reflects this PR — feature list, tech stack, SDK levels, state machine, commands. Never stale.
- [ ] Unit tests: 0 failures (count: __)
- [ ] Instrumented tests: 0 failures (count: __)
- [ ] Autonomous onboarding loop: PASS
- [ ] App installed + launched on an emulator; no FATAL in logcat
- [ ] Screenshot captured and attached to the PR
- [ ] Coverage gaps stated + manual QA checklist provided
```
