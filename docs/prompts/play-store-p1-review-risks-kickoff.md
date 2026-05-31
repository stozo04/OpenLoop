# Play Store P1 — Review Risks Kickoff Prompt

Copy everything below the line into a fresh Claude Code / Cursor session with the OpenLoop repo mounted. This kickoff resolves **P1 — Likely Play review questions** from [Issue #39](https://github.com/stozo04/OpenLoop/issues/39).

---

## Session Prompt — Play Store P1 Review Risks

You are working on **OpenLoop** — an open-source Android camera app (Kotlin/Jetpack Compose) for speed-controlled video loops. Repo: `stozo04/OpenLoop`. Package: `io.github.stozo04.openloop`. Owner: Steven Gates (@stozo04).

**Timeline:** Internal testing now → public Production ~2 weeks. P1 items reduce **Play review rejection risk** and **user confusion** at launch.

## Critical Rule — Do Not Trust Your Training Data

Before claiming how Play reviews permissions, large-screen adaptivity, or foreground service policies work, **web-search `developer.android.com` and Play policy docs in this session**. Training data is stale on API 36 behavior changes.

## P1 Scope (from Issue #39)

| # | Risk | Owner decision | Primary action |
|---|------|----------------|----------------|
| P1-1 | **`RECORD_AUDIO` declared but export strips audio** | **No audio — video-only app.** Loops are silent forever. | **Remove `RECORD_AUDIO`** end-to-end |
| P1-2 | **Large-screen adaptivity (targetSdk 36)** | Camera app; Fold is primary device | Verify UX on sw600dp+ / fold; document or fix |
| P1-3 | **Foreground service policy** | Background export in scope | **Tracked in Issue #40** — do not duplicate; ensure P1 docs reference #40 |

**This session owns P1-1 and P1-2.** Issue #40 has its own kickoff.

## Before Writing Any Code — Read These Files

1. **`CLAUDE.md`**
2. **Every file in `docs/lessons_learned/`** — 006 (permission rationale), 004 (no Context on VM), 012 (camera screen).
3. **`docs/DEFINITION_OF_DONE.md`**
4. **`docs/ANDROID_STANDARDS.md`** — permissions section
5. **`docs/play-store/data-safety.md`** + **`docs/play-store/store-listing.md`** — must stay consistent after audio removal
6. **`docs/android-16/README.md`** + **`docs/android-16/behavior-changes-targeting-16.md`** — large-screen rules
7. **Issue #39** P1 section

## Phase 1 — Branch + baseline

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
git checkout main
git pull --rebase
git checkout -b feature/remove-record-audio-permission
.\gradlew.bat clean assembleDebug --console=plain; echo "EXIT=$LASTEXITCODE"
```

## Phase 2 — Web-verify (search before coding)

| Topic | Search target |
|-------|---------------|
| Permission best practices / minimal permissions | `developer.android.com/training/permissions/usage-notes` |
| Request only needed permissions | `developer.android.com/quality/privacy-and-security` |
| Android 16 large screen / orientation overrides (targetSdk 36) | `developer.android.com/develop/adaptive-apps/guides/app-orientation-aspect-ratio-resizability` |
| Camera without audio recording | CameraX `prepareRecording` without `withAudioEnabled` |

Document findings in PR description with URLs.

## Phase 3 — P1-1: Remove RECORD_AUDIO (primary code task)

**Owner mandate:** OpenLoop is **video-only**. Exported boomerangs use `setRemoveAudio(true)`. Microphone permission invites Play review questions and user distrust.

### Files to touch (grep to confirm nothing missed)

| File | Change |
|------|--------|
| `app/src/main/AndroidManifest.xml` | Remove `RECORD_AUDIO` uses-permission |
| `app/src/main/java/.../MainActivity.kt` | Remove from `REQUIRED_PERMISSIONS`; update rationale copy |
| `app/src/main/java/.../camera/CameraManager.kt` | Remove `withAudioEnabled()` path entirely — never record audio |
| `app/src/main/java/.../ui/OpenLoopViewModel.kt` | Remove audio from permission checks / `SecurityException` catch for audio |
| `app/src/androidTest/.../PermissionExplanationScreenTest.kt` | Update expected strings (Camera only) |
| `app/src/test/.../OpenLoopViewModelTest.kt` | Remove/update RECORD_AUDIO denial tests |
| `docs/play-store/store-listing.md` | Remove microphone bullet from PERMISSIONS section |
| `docs/play-store/data-safety.md` | Update camera-only rationale if microphone mentioned |
| `docs/privacy-policy.html` / `docs/play-store/privacy-policy.md` | Camera only — verify consistency |

**Do not** add a "optional audio" toggle — owner rejected audio permanently.

### Permission UX after change

- Rationale screen: **Camera only** — "OpenLoop needs camera access to record video for your loops."
- Permanent denial flow: settings deep link still works with single permission.

## Phase 4 — P1-2: Large-screen / Fold adaptivity

**Context:** Apps targeting API 36 on devices with smallest width ≥600dp may have orientation/resizability restrictions ignored by the system ([adaptive apps guide](https://developer.android.com/develop/adaptive-apps/guides/app-orientation-aspect-ratio-resizability)).

**Primary device:** Pixel Fold 10 — test **both folded and unfolded**:
- Camera viewfinder layout
- Editor preview aspect ratio
- Trim bar usability
- Gallery grid

**Emulator:** Create sw600dp tablet AVD (API 36) — verify app launches and core flow works without broken letterboxing.

**Acceptable outcomes:**
1. **Works well** — document in PR with Fold + tablet screenshots.
2. **Camera UX broken on tablet** — document known limitation in store listing OR add `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` per Google docs (search current opt-out rules for API 36 — opt-out removed at API 37).

Do **not** block launch on perfect tablet camera UX unless Play policy requires it — but must not crash.

## Phase 5 — Tests + DoD

```powershell
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest :app:lintDebug --console=plain
.\gradlew.bat assembleRelease --console=plain; echo "EXIT=$LASTEXITCODE"
```

Manual QA on **Pixel Fold 10**:
- [ ] Fresh install → permission dialog asks **Camera only** (not microphone)
- [ ] Record 5 s clip → save loop → no crash without audio permission
- [ ] Unfolded + folded camera + editor — no layout catastrophe
- [ ] Import from Photo Picker still works

**Screenshot:** Permission rationale screen (camera only) + editor on Fold unfolded.

## Phase 6 — PR

- Title: `Remove RECORD_AUDIO — video-only app (Play P1)`
- Link Issue #39
- Checklist: P1-1 + P1-2 verification
- Note: Issue #40 still required before Production for background Loopifying

## Behavioral Rules

- **Minimal diff** — permission removal only; no unrelated refactors.
- **Docs stay honest** — update store listing + privacy policy in same PR.
- **Web-search every policy claim.**

## When to Stop and Ask Steven

- Removing audio breaks CameraX recording on a specific device (unlikely without `withAudioEnabled`).
- Fold unfolded layout needs a design decision (letterbox vs fill).
- Play policy search suggests camera-only video apps still need microphone (cite source — likely false for silent export).
