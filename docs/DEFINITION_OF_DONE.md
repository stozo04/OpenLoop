# Definition of Done — the "Ready for PR" verification gate

**A change is not "done" because it compiles. It is done when it has been built, tested, *run*, and honestly reported.** This is the gate every non-trivial change must clear before it is called done or opened as a PR. It applies to humans and to Claude Code agents working in this repo.

The guiding principle: **don't trust "it compiles" — prove it.** Prove it builds clean, prove the tests pass, prove the app actually *runs*, and be explicit about what you could not verify.

---

## Production zero-error rule (non-negotiable)

**OpenLoop is live in Production and reachable by billions of users. Any error the agent encounters while working — a failing test, a compile error, a lint error, a crash — MUST be fixed before a PR is created. No exceptions, even for pre-existing failures the agent did not introduce.**

- "Not my change" is **not** a reason to leave a red build. If you touch a module and its tests don't compile or don't pass, you fix them as part of the work. If the fix is genuinely out of scope, you **stop and flag it to the owner and get explicit direction** — you do not open a PR on top of a known-broken baseline.
- A PR must be opened only from a **fully green** state: clean debug + release build, **0 test failures**, **0 lint errors, 0 lint warnings, 0 compiler warnings, 0 Markdown/spelling/inspection findings**. A known failing test in the branch is a release blocker, full stop.
- **The pre-PR sweep is the gate, and it is mechanical.** `.\scripts\pre-pr-sweep.ps1` runs every check below and writes `build/sweep-receipt.json` only when all of them are green. A Claude Code `PreToolUse` hook (`scripts/hooks/require-sweep.mjs`, wired in `.claude/settings.json`) refuses `gh pr create` and the GitHub `create_pull_request` tool unless that receipt exists **for the current `HEAD` on a clean tree** — so the sweep is, by construction, the last thing that runs after the final commit. Humans run the same script; there is no other route to a PR.
- If you discover the breakage was already on `main`, that makes it **more** urgent, not less — a broken gate on `main` means the safety net is down for every future change. Repair it (or escalate) immediately; never build on top of it.
- Capture the green proof (build verdict + exit 0 + test counts, per the gate below) in the PR.

---

## The gate (run top to bottom)

> **One command runs steps 1–4 and the text gates and enforces zero across the board:**
>
> ```powershell
> .\scripts\pre-pr-sweep.ps1                         # full sweep — emulator/device attached, Inspect Code export present
> .\scripts\pre-pr-sweep.ps1 -SkipConnected -SkipInspectCode   # what an agent without Studio/emulator can run; the PR must say so
> ```
>
> It reports every gate (it never stops at the first red), logs to `build/sweep.log`, and writes
> `build/sweep-receipt.json` on green. The steps below are what it runs, kept here so a human can
> reproduce any one of them by hand.

### 0. Baseline — before you change anything

Capture a green build of the *starting* state so any later failure is unambiguously yours:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"   # Android Studio's bundled JDK
.\gradlew.bat clean assembleDebug --console=plain
```

### 1. Build — debug AND release, genuinely green

Release matters: it runs R8/shrinking and resource crunching that debug skips, and catches things debug never will (it's how we found mislabeled JPEG drawables and R8 issues).

```powershell
.\gradlew.bat clean assembleDebug assembleRelease --console=plain; echo "EXIT=$LASTEXITCODE"
```

See **["Genuinely green"](#what-genuinely-green-means)** below — a finished command is not the same as a passed build.

### 2. Requirement / artifact checks

Verify the things a build alone doesn't prove. Example for this app — **16 KB native-lib alignment** (see [Lesson 011](lessons_learned/011-16kb-uncompressed-native-libs.md)):

```text
<sdk>/build-tools/<ver>/zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release-unsigned.apk
# .so lines must read "(OK)" at 16384-multiple offsets — NOT "(OK - compressed)"
```

### 3. Static analysis — code inspection (Android Studio "Inspect Code", headless)

Reproduce both Inspect Code engines and clear them. Full design + severity rules: **[`docs/STATIC_ANALYSIS.md`](STATIC_ANALYSIS.md)**.

```powershell
.\gradlew.bat :app:lintDebug --console=plain        # Engine 1 — Android Lint (the automated gate)
```

There is no lint baseline, so the report must show **zero `severity="Error"` entries and zero `severity="Warning"` entries** (the sweep parses the XML; only the version-freshness checks `GradleDependency` / `NewerVersionAvailable` / `AndroidGradlePluginVersion` are advisory). Then **Engine 2** (IDE inspections + proofreading): in Android Studio run **Code → Inspect Code** with the custom scope **OpenLoop Tracked**, export the result as HTML into `build/inspect-export/`, and let the sweep parse it (`python scripts/inspect-report.py build/inspect-export/index.html` — zero hard findings in tracked files). The headless `inspect.bat` is vacuous on this machine (`STATIC_ANALYSIS.md`), so the IDE run is the real one. **Do not introduce a `lint-baseline.xml` to silence findings** — a generated baseline swallows every issue currently in the tree, including ones the PR just added. Fix it, or suppress it at the source with a stated reason.

The text gates run alongside (all zero, whole repo, no baseline): `markdownlint-cli2`, `python scripts/md-table-align.py`, `markdown-link-check`, `cspell` over every tracked text file (legit terms go into `cspell.json` `words`; `python scripts/sync-ide-dictionary.py` keeps the IDE dictionary identical), and JSON validity.

### 4. Automated tests — unit and instrumented

```powershell
.\gradlew.bat testDebugUnitTest --console=plain                 # JVM, no device
$env:ANDROID_SERIAL = "emulator-XXXX"                            # pin the device if more than one is attached
.\gradlew.bat connectedDebugAndroidTest --console=plain         # needs a booted emulator/device
```

Read the actual counts (`tests=".." failures=".." errors=".."` in `app/build/.../*-results/`); confirm **0 failures**, not just `BUILD SUCCESSFUL`.

### 5. Run it for real — boot, install, launch, screenshot

This is the step that separates "should work" from "works." Automated tests miss crashes-on-launch, missing/mislabeled assets, and layout breakage. Boot an emulator, install the APK, launch the app, and capture a screenshot as **proof**.

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

> AGP uninstalls the app after `connectedAndroidTest`, so re-`install` before launching. `pm clear io.github.stozo04.openloop` first if you need a fresh first-run (e.g. to see onboarding).

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
- [ ] Baseline green before changes (clean assembleDebug)
- [ ] clean assembleDebug + assembleRelease: BUILD SUCCESSFUL, exit 0, zero e:
- [ ] Requirement checks pass (e.g. zipalign -c -P 16 shows real (OK))
- [ ] `.\scripts\pre-pr-sweep.ps1` GREEN on the final commit: build 0 e:/0 w:, zipalign, Lint 0/0, tests 0 failures, Markdown + tables + links + cspell + JSON all zero (receipt: build/sweep-receipt.json)
- [ ] Inspect Code (Engine 2) export parsed to 0 hard findings — or the PR says it was SKIPPED and why
- [ ] Play-facing docs aligned (owner rule, 2026-08-24): permission/telemetry/storage changes → data-safety.md + privacy policy (md + html, new effective date); lens/feature changes → store-listing.md (+ docs/index.html, root README.md)
- [ ] Agent memory aligned (owner rule, 2026-08-24, Claude sessions): the project memory (`MEMORY.md` index + entries) reflects what this PR changed — new durable facts captured, entries this PR made stale corrected or deleted. Memory is never left stale.
- [ ] Root `README.md` aligned (owner rule, 2026-08-24): the GitHub-facing README reflects this PR — feature list, tech stack, SDK levels, state machine, commands. Never stale.
- [ ] Unit tests: 0 failures (count: __)
- [ ] Instrumented tests: 0 failures (count: __)
- [ ] App installed + launched on an emulator; no FATAL in logcat
- [ ] Screenshot captured and attached to the PR
- [ ] Coverage gaps stated + manual QA checklist provided
```
