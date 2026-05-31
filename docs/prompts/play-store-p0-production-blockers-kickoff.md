# Play Store P0 — Production Blockers Kickoff Prompt

Copy everything below the line into a fresh Claude Code / Cursor session with the OpenLoop repo mounted. This kickoff resolves **P0 — Must resolve before Production** from [Issue #39](https://github.com/stozo04/OpenLoop/issues/39).

---

## Session Prompt — Play Store P0 Production Blockers

You are working on **OpenLoop** — an open-source Android camera app (Kotlin/Jetpack Compose) for creating speed-controlled video loops ("Boomerangs"). Repo: `stozo04/OpenLoop`. Package: `io.github.stozo04.openloop`. Owner: Steven Gates (@stozo04). Apache 2.0.

**Timeline:** Internal testing first; **public Production in ~2 weeks**. P0 items are hard gates — nothing ships to Production until these are checked off.

**Your mission:** Close every P0 blocker from Issue #39. This is a **verification + submission** sprint, not a feature sprint. Code changes are only in scope when QA reveals a defect.

## Critical Rule — Do Not Trust Your Training Data

Your knowledge cutoff could be a year old. **Do not assume** you know current Google Play requirements, target API floors, 16 KB page-size rules, Data safety form wording, or internal-testing vs production promotion steps. Before making any claim about Play policy or Android behavior, **web-search `developer.android.com` and `support.google.com/googleplay` first**. If you write "Google requires X" without searching in this session, stop and search.

## P0 Checklist (authoritative scope)

From Issue #39 and `docs/play-store/README.md`:

| # | Blocker | Owner | Agent role |
|---|---------|-------|------------|
| P0-1 | **Real-device QA matrix** — API 26 / 29 / 30 / 33 / 36; capture, import (HDR + 4K), reverse, render, share | Steven + agent | Execute matrix; document results; file defects |
| P0-2 | **Developer account + signed AAB** | Steven (account); agent (build) | Verify `./gradlew :app:bundleRelease` green; guide keystore steps |
| P0-3 | **Store assets** — icon 512×512, feature graphic 1024×500, ≥2 phone screenshots | Steven captures; agent specs | Verify specs; capture screenshots during QA if device available |

**Out of scope for this prompt:** P1/P2 items (#39), Issue #40 (background export), Issue #41 (Loopifying perf) — separate kickoffs.

## Owner context (do not re-litigate)

- **Primary test device:** Google Pixel Fold 10 (Android Pro Fold) — use for all physical QA rows first.
- **No audio in loops** — video-only product (see P1 kickoff for permission cleanup).
- **Quality first** — no Fast/HD export toggle.
- **30 s max clip** at capture/import (`MAX_RECORDING_MS = 30_000L`).
- Fold-only testing is **not sufficient** for P0-1 — emulators required for API 26/29/30/33 floors.

## Before Any Work — Read These Files (in order)

1. **`CLAUDE.md`** — operating instructions.
2. **Every file in `docs/lessons_learned/`** — especially 005 (target API), 011 (16 KB), 013 (media failures), 020 (HDR import).
3. **`docs/DEFINITION_OF_DONE.md`** — the verification gate (mandatory for any code fix this sprint spawns).
4. **`docs/play-store/README.md`** — pre-submission checklist (source of truth).
5. **`docs/play-store/store-listing.md`** — asset specs + screenshot checklist.
6. **`docs/play-store/release-signing-and-aab.md`** — AAB build + signing.
7. **`docs/play-store/data-safety.md`** — Data safety answers (verify still accurate).
8. **`docs/TEST_COVERAGE.md`** — test conventions.
9. **Issue #39** on GitHub — full audit context.

## Phase 1 — Green baseline + release artifact

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
git checkout main
git pull --rebase
.\gradlew.bat clean assembleDebug assembleRelease --console=plain; echo "EXIT=$LASTEXITCODE"
```

Then verify 16 KB alignment per `docs/DEFINITION_OF_DONE.md`:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\<ver>\zipalign.exe" -c -P 16 -v 4 `
  app\build\outputs\apk\release\app-release-unsigned.apk
```

If `keystore.properties` exists:

```powershell
.\gradlew.bat :app:bundleRelease --console=plain; echo "EXIT=$LASTEXITCODE"
```

Document: debug/release exit codes, zipalign `(OK)` at 16384, AAB path if signed.

## Phase 2 — Web-verify Play requirements (search first)

Search and cite live URLs for:

| Topic | Start here |
|-------|------------|
| Target API level for new apps (2025–2026) | `developer.android.com/google/play/requirements/target-sdk` |
| 16 KB page size (Nov 2025+) | `developer.android.com/guide/practices/page-sizes` |
| Internal testing track promotion | `support.google.com/googleplay/android-developer` (internal testing) |
| Store listing asset specs | `support.google.com/googleplay/android-developer/answer/9866151` |
| Data safety form | `developer.android.com/privacy-and-security/declare-data-use` |

Confirm OpenLoop's `targetSdk 36`, 16 KB packaging, and "no data collected" posture still match live policy. Flag any drift in a comment on Issue #39.

## Phase 3 — QA matrix (P0-1)

Create or update a **QA results artifact** in the PR or Issue #39 comment (table format). Minimum rows:

| Row | API | Surface | Flow | Pass/Fail | Notes |
|-----|-----|---------|------|-----------|-------|
| 1 | 36 | Pixel Fold 10 | Capture 10 s → Trim → F→R @2× → Save → Share | | |
| 2 | 36 | Pixel Fold 10 | Import 4K SDR 10 s → F→R @2× → Save | | |
| 3 | 36 | Pixel Fold 10 | Import HDR 10 s → F→R → Save (Lesson 020) | | |
| 4 | 36 | Pixel Fold 10 | Portrait rear camera → verify rotation both halves (Lesson 019) | | |
| 5 | 36 | Pixel Fold 10 | 30 s max capture auto-cap | | |
| 6 | 26 | Emulator | Photo Picker import (GMS backport) → Trim → Save | | |
| 7 | 29 | Emulator | Capture or import → full editor path | | |
| 8 | 33 | Emulator | POST_NOTIFICATIONS N/A yet — baseline capture path | | |

**Pass criteria per row:** no crash, loop plays in gallery, share sheet opens, no visible seam stutter, no green macroblocks on reversed half.

Run automated tests in parallel:

```powershell
.\gradlew.bat testDebugUnitTest --console=plain; echo "EXIT=$LASTEXITCODE"
$env:ANDROID_SERIAL = "<fold-or-emulator-serial>"
.\gradlew.bat connectedDebugAndroidTest --console=plain; echo "EXIT=$LASTEXITCODE"
.\gradlew.bat :app:lintDebug --console=plain; echo "EXIT=$LASTEXITCODE"
```

Any failure → fix in a focused branch OR file a new GitHub issue with repro steps; do not mark P0-1 done with open defects.

## Phase 4 — Store assets (P0-3)

Per `docs/play-store/store-listing.md`:

- [ ] **512×512** app icon (PNG, no transparency for Play icon rules — verify current policy via web search)
- [ ] **1024×500** feature graphic
- [ ] **≥2 phone screenshots** — capture during QA (editor with speed slider, gallery with loop, or Loopifying screen)

If generating assets from emulator screenshots, note device density and crop to phone aspect. Attach to internal testing release notes.

## Phase 5 — Play Console readiness (P0-2) — owner-operated, agent-assisted

Agent prepares a **submission packet comment** for Steven with copy-paste blocks from:

- `docs/play-store/data-safety.md`
- `docs/play-store/content-rating.md` (for awareness — P2 owns form entry)
- `docs/play-store/store-listing.md`
- Privacy policy URL: `https://stozo04.github.io/OpenLoop/privacy-policy.html`

Steven must: create developer account, upload AAB to **Internal testing**, complete Data safety + store listing minimum fields.

Agent verifies repo docs match app behavior (especially permissions list after any P1 audio removal).

## Phase 6 — Definition of Done

For any code fixes spawned by QA:

- Full DoD gate in `docs/DEFINITION_OF_DONE.md`
- Screenshot proof of fixed flow on Fold or emulator
- PR references Issue #39 P0 row that failed

For this **verification sprint** (no code): deliverable is the **completed QA matrix table** + green build log + asset checklist — posted to Issue #39.

## Behavioral Rules

- **PRD-first for fixes** — if QA finds a bug, write a one-paragraph fix plan before coding.
- **Pushback** — if a P0 row cannot run (no API 26 emulator), say so and propose the minimum substitute.
- **Honesty** — "Fold only" is insufficient; state coverage gaps explicitly.
- **Do not** start Issue #40 or #41 work in this session — stay on P0 gates.

## When to Stop and Come Back to Steven

- Developer account / Play Console access needed (agent cannot log in).
- `keystore.properties` missing and release AAB cannot be signed.
- QA reveals a **P0 defect** needing a multi-day fix (background export #40, etc.) — recommend reordering launch timeline.
- Live Play policy contradicts `docs/play-store/*` — stop and propose doc + app updates before submission.
- Green baseline fails on `main`.

## Success Definition

P0 is **done** when:

1. QA matrix rows 1–8 recorded with Pass (or documented Fail + linked fix issue).
2. Release build + zipalign verified green.
3. Store asset checklist complete (files ready for Console upload).
4. Internal testing AAB uploaded (owner confirms) OR explicit blocker documented with date.
