# HEY CLAUDE, IT'S ME 🪃

A note from a past Claude Code session to you, the next one. Steven asked me to leave this
at the repo root (yes, against the "docs go in `docs/`" rule — he made the call, so enjoy your
one sanctioned exception). It's not the source of truth; it's the hand on your shoulder pointing
you at the source of truth, plus the stuff I wish someone had told *me* at minute one.

_Written 2026-05-28 by the Opus 4.7 session that took OpenLoop to `targetSdk 36`._

---

## Who you're working with

Steven — solo dev, Android/Kotlin, sharp, builds with AI on purpose and well. What he actually
wants from a CC partner (this is the real gold):

- **Take initiative to ensure success.** The moment this session clicked for him was when I didn't
  just *say* "the build passes" — I booted an emulator, installed the app, launched it, and
  screenshotted onboarding. Then later drove his actual phone over adb. He didn't ask for either.
  Close the loop. Prove it works. That instinct is the job.
- **Push back and flag contradictions.** When "use latest deps" collided with the doc's "keep
  Kotlin 1.9.22" decision, the right move was to stop and surface the tension, not silently pick one.
  He values that more than speed.
- **No fluff, no sycophancy.** Rigor and breadth, said plainly. He can read a diff.
- **PRD-first, sign-off before build-file changes, explicit "proceed" before anything irreversible**
  (especially Play submission). He'll tell you to go autonomous when he wants that.

## How we work here (read these — they're not optional)

1. `CLAUDE.md` — operating instructions. Start here every session.
2. `docs/DEFINITION_OF_DONE.md` — **the bar.** A change isn't done because it compiles. Baseline →
   clean build (debug **and** release) → requirement checks → unit + instrumented tests → **run the
   app and screenshot it** → state what you couldn't verify → attach proof to the PR. This came out
   of *this* session and Steven made it a hard standard. Live it.
3. `docs/lessons_learned/` — read every file. They were expensive to learn. Don't re-pay.
4. **Do NOT trust your training data on versions/behavior.** Web-verify against developer.android.com
   and Google Maven *this session*. The "latest stable" you remember is already stale. (Mine was:
   the deps had moved two minor versions past what the plan named.)

## Environment cheat-sheet (Windows — hard-won, saves you an hour)

- **Java:** no `java` on PATH. Use Android Studio's JBR:
  `JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"` (it's JDK 21; fine for AGP 8.13 / Gradle 9).
- **Build:** `.\gradlew.bat ...` (PowerShell). Capture the real result —
  `BUILD SUCCESSFUL` **and** `$LASTEXITCODE -eq 0` **and** zero `e:` lines.
- **The `| tail` trap:** piping Gradle through `| tail` gives you *tail's* exit code. A failed build
  will look green. It fooled me once on the baseline. Read the verdict line itself.
- **adb on-device paths + Git Bash = pain:** MSYS rewrites `/sdcard/foo` into
  `C:/Program Files/Git/sdcard/foo` and the pull fails. Run those adb commands from **PowerShell**
  (or `MSYS_NO_PATHCONV=1`).
- **Foldables / multi-display:** `exec-out screencap -p > file` gets a "Multiple displays" warning
  bled into the PNG and corrupts it. Use `screencap -p /sdcard/x.png` then `pull`.
- **Multiple devices / ghost `offline` entries:** always `-s <serial>` (or `$env:ANDROID_SERIAL`);
  `adb kill-server; adb start-server` clears dead ghosts.
- SDK lives at `C:\Users\gates\AppData\Local\Android\Sdk` (`platform-tools\adb.exe`,
  `build-tools\<ver>\zipalign.exe`, `emulator\emulator.exe`).
- Steven runs a **physical Pixel 10 Pro Fold (API 36)** — adb commands hit *real hardware*. Be
  considerate (force-stop closes his app on his screen). The `/reset-storage` skill handles the
  OpenLoop reset safely.

## Gotchas this codebase will throw at you

- **16 KB native libs:** `zipalign -c -P 16` reading `(OK - compressed)` is a *fake* pass. You need
  `useLegacyPackaging = false` so libs are uncompressed, and you get a real `(OK)`. See lesson 011.
- **Latest AndroidX ⇒ Kotlin 2.x:** CameraX/Media3's newest releases ship Kotlin 2.1 metadata; a 1.9
  compiler can't read it. Migrating means the Compose compiler moves to the
  `kotlin.plugin.compose` Gradle plugin and `kotlinOptions` → `compilerOptions`.
- **Release-only failures are real:** R8 + resource crunch catch things debug never will. Three
  "PNG" onboarding drawables were actually JPEGs (mislabeled `.png`) — fine in debug, fatal in the
  release crunch. Always build release too.

## Where things stand (snapshot — verify with `git`/`gh`, this rots)

- **API 36 upgrade:** shipped on `feature/target-sdk-36` → **PR #15** (open). Build/tests/16 KB green.
- **Issue #7** (the upgrade) left **open** on purpose — closes after Steven's on-device QA.
- **Issue #14** — Play-release mechanics (signing, .aab, Data Safety, privacy policy), fully spec'd.
- **Still pending:** PR #15's `pr-reviewer` standards gate (repo merge requirement), the unfolded
  ≥600 dp large-screen pass, and the full camera→preview→gallery→delete walkthrough on API 36.
- **Skills:** `/reset-storage` (re-show onboarding; asks keep-vs-delete videos) and `pr-reviewer`
  (the standards gate). Both under `.claude/skills/`.

## Last thing

Steven's a great partner — give him the same energy back: do the work, prove it ran, tell him the
truth about what you didn't check, and don't be a pushover when something smells wrong. You've got
this. Go close some loops.

— your past self 🪃
