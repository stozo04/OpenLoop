# Android 16 Doc-Prep — Get the docs ready for the `targetSdk 36` upgrade

**GitHub Issue:** none of its own — this is **preparation for [Issue #7](https://github.com/stozo04/OpenRang/issues/7)** (the actual SDK upgrade). Unnumbered folder on purpose: the `NNN-` prefix maps a folder to a GitHub issue number, and this work has no separate issue.
**Branch:** `feature/update-api`
**Status:** IN PROGRESS
**Last updated:** 2026-05-28

---

## Problem statement

OpenRang must raise `targetSdk` from **34 → 36** to publish on Google Play (Issue #7). Before any build file changes, the project's documentation needed to:

1. Capture Android 16's behavior changes, features, and migration process **in OpenRang terms** — so an agent or contributor knows exactly which changes apply to a zero-network, on-device camera app and which don't.
2. Reconcile that new, version-specific material with the **evergreen** `docs/ANDROID_STANDARDS.md` without conflating the two.
3. Make the **current vs. target SDK state explicit** in `CLAUDE.md` and `README.md`, with links to the authoritative Google sources.

This branch is **documentation only**. It changes no Kotlin and no Gradle config.

## Scope

**In scope (this branch):**

- `docs/android-16/` — a durable Android 16 (API 36) knowledge hub: a README index plus a summary of each Google source page (behavior changes, summary, migration, features, progress-centric notifications, framework API diff). Each file leads with its source URL and an *Impact on OpenRang* verdict.
- `docs/ANDROID_STANDARDS.md` — §8 dates corrected against verified Google data; a new **§11 Android Version Targeting** section stating the target-36 rules, each marked `Status: pending — Issue #7`.
- `CLAUDE.md` + `README.md` — explicit SDK-state notes (34 today → 36 in progress) and references to the hub.
- This implementation record.

**Out of scope (belongs to Issue #7, a later branch):**

- Any `app/build.gradle.kts` change — the `compileSdk` / `targetSdk` bump, AGP / Gradle bumps, CameraX / Media3 bumps, the 16 KB page-size work.
- Any code change — the edge-to-edge inset audit, predictive-back migration, large-screen adaptive layout.
- Play Store release mechanics (signing, `.aab`, Data Safety form).

## Architecture — how the docs fit together

| Doc | Role | Lifespan |
|-----|------|----------|
| [`docs/ANDROID_STANDARDS.md`](../../ANDROID_STANDARDS.md) | Evergreen "how we build" rules (all SDK versions) | Permanent, at `docs/` root |
| [`docs/android-16/`](../../android-16/README.md) | Version-specific Android 16 reference (verbatim + summarized Google pages) | Durable hub — does **not** move to `completed/` |
| [`docs/active/007-target-sdk-upgrade/`](../007-target-sdk-upgrade/IMPLEMENTATION.md) | The actual upgrade plan & checklist (code + build) | Moves to `completed/` when #7 ships |
| `docs/active/android-16-doc-prep/` (this folder) | Tracks the doc-prep deliverable | Moves to `completed/` when this branch merges |

**Lesson 007 boundary.** Because this branch lets docs run *ahead* of code, every Android-16 rule that the code (API 34) does not yet satisfy is marked `Status: pending — Issue #7`. No doc asserts OpenRang already targets 36, is edge-to-edge, or handles predictive back. A dedicated phase in the [007 plan](../007-target-sdk-upgrade/IMPLEMENTATION.md) flips those markers and bumps the API numbers when the corresponding code merges, keeping doc and code convergent.

## Implementation steps (done on this branch)

1. **Verified** Google Play target-SDK requirements (2026-05-28): floor is **API 35** since Aug 31 2025; **API 36** expected ~Aug 2026 but with no published date yet. Corrected `ANDROID_STANDARDS.md` §8, which had asserted the Aug-31-2026 / API-36 date as fact.
2. **Built `docs/android-16/`** — README index + six reference docs, each fetched live from `developer.android.com` (not from model memory) and given an OpenRang impact verdict.
3. **Updated `ANDROID_STANDARDS.md`** — verified §8 dates; new §11 with target-36 rules (edge-to-edge, predictive back, adaptive layouts), each `pending — Issue #7`; added Android 16 links.
4. **Updated `CLAUDE.md` + `README.md`** — SDK-state notes and hub references.
5. **Wrote this record.**
6. **Updated the [007 plan](../007-target-sdk-upgrade/IMPLEMENTATION.md)** — added a doc-sync phase, clarified that implementation lands on a later branch, cross-linked the hub, and fixed a stale Lesson-002 citation.

## Testing plan

No code changes ⇒ no unit or UI tests. Validation is documentation-level:

- IDE reports **zero Markdown "General Errors"** for the new/edited docs (Lesson 010 — code fences parse).
- All internal relative links resolve.
- **Lesson 007 pass:** no doc claims compliance the code lacks; every target-36 rule is marked pending.
- Date-sensitive claims carry a "verified 2026-05-28 / re-verify before release" stamp.

## Acceptance criteria

- [ ] `docs/android-16/` hub complete; each file has a source URL and an OpenRang verdict.
- [ ] `ANDROID_STANDARDS.md` §8 dates match verified Google data; §11 rules all marked `pending — Issue #7`.
- [ ] `CLAUDE.md` + `README.md` state current SDK (34) and in-progress target (36) with links.
- [ ] No doc asserts the code already targets 36 / is edge-to-edge / handles predictive back.
- [ ] The 007 plan has a phase to flip the doc markers + bump API numbers when code lands.
- [ ] Branch contains no build or code changes.

## Reversibility note

All changes are documentation, version-controlled. Revert = `git restore` / branch reset. Nothing here is externally visible or irreversible.
