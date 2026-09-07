---
name: cut-release
description: >-
  Runs the OpenLoop Play release, which is two dispatched GitHub Actions workflows rather than a
  local sequence: `release.yml` bumps the version, merges its own bump PR, then builds and signs
  the AAB from the resulting merge sha and publishes it as a run artifact; `tag.yml` cuts the tag
  afterwards. Use when the user says "/cut-release", "cut a release", "cut release", "ship a
  release", "start the release process", "bump the version and release", or wants to move a merged
  `main` toward a tagged Play release. The only manual step is uploading the `.aab` to Play
  Console; the tag still means "this shipped", so it is cut after that upload, never before.
---

# cut-release — OpenLoop Play release

Follow [shared operating instructions](../../../docs/OPERATING_INSTRUCTIONS.md) for authorization, scope, verification, and blocker reporting.

**Releases run in GitHub Actions as of 2026-09-07.** Do not bump the version by hand, do not
build the bundle locally, and do not cut the tag with a local script — the pipeline does all of
it, with guards that a manual run does not have. Your job is to dispatch the right workflow,
read what it reports, and stop at the one gate a workflow cannot clear.

## How a release runs

**Run 1 — `Release · build signed AAB` (`.github/workflows/release.yml`)**

Reads the current `versionCode`, bumps it, opens and merges its own bump PR, resolves the merge
sha, then builds and signs from that exact sha and publishes the `.aab` plus drafted notes as a
run artifact. Guards it enforces, so you do not have to:

- `versionName` must equal `1.0.<versionCode>` before it will bump; it stops rather than guess a
  new scheme if that convention ever breaks
- the bump diff must be exactly +2/−2 in `app/build.gradle.kts`
- the build sha must be an ancestor of `origin/main`, with the intended version at that sha
- the keystore must open with the configured alias before the build starts
- `jarsigner -verify` must report `jar verified`

`dry_run: true` builds `main` as it stands with no bump, PR or merge. Use it to prove the build
half after any change to the workflow, the toolchain, or the signing secrets.

**Run 2 — `Release · tag` (`.github/workflows/tag.yml`)**

Takes `version`, `sha`, optional `title`, and the release run's `run_id`. Downloads that run's
bundle so `scripts/tag-release.ps1`'s "was this actually built" check is real, then calls the
script unmodified to cut the tag and GitHub release.

## What you do

1. Dispatch run 1. From a machine with `gh`: `gh workflow run release.yml` (add
   `-f dry_run=true` for a build-only run). Otherwise tell the owner to run it from the Actions
   tab — you cannot dispatch it from a session without `gh`, and should say so plainly rather
   than falling back to doing the release by hand.
2. When it finishes, read the run summary: it prints the version, the build sha, the bundle path
   and the exact `tag.yml` inputs to use next.
3. Hand the owner the artifact link and the drafted notes. **Stop there** — see below.
4. After the owner confirms the Play upload, dispatch run 2 with the version, sha and run id from
   that summary.

## Stop — wait for the Play upload (hard stop)

Nothing in this session can upload to Play Console or confirm that someone did. **Do not dispatch
`tag.yml` until the owner explicitly confirms the `.aab` is uploaded.** Refuse even if asked to
"just do it all" — the tag means "this shipped", and only the owner can establish that.

This is now the *only* hard stop. The old Stop A (a required approving review on the bump PR) is
gone: the workflow merges its own bump PR, which is a deliberate owner decision, not an oversight.

## Detect where a release currently stands

Do not assume you are starting fresh. Version contents, commit history and GitHub's run and PR
data are authoritative; branch names and PR titles are only hints.

1. Read `versionCode`/`versionName` from `app/build.gradle.kts` and the latest tag
   (`git tag --sort=-v:refname`, or `gh release list`).
2. `versionName` == latest tag, no release run in flight → nothing started; dispatch run 1.
3. A bump merged but no tag for it → find the release run that produced it
   (`gh run list --workflow=release.yml`), take the build sha and run id from its summary, and
   resume at the Play-upload stop.
4. A tag exists for the current `versionName` → run the two final checks below before reporting
   the release complete. A tag alone does not prove the release is live or the bundle verified.
5. A release run failed partway → read the failing step. If it failed *before* the bump merged,
   nothing was changed and re-dispatching is safe. If it failed *after*, `main` already carries
   the bump: fix the cause and re-dispatch with `dry_run: true` to build the same version rather
   than bumping again.

GitHub issues and PRs share one number sequence. If `gh pr view <number>` says a number is not a
PR, inspect recent merged PRs and the version-bump history, report the correction, and never
silently treat an issue number as a merge sha.

## No sweep on a release bump

`scripts/pre-pr-sweep.ps1` is the gate for **feature and bug PRs**. It does not run on a release
bump, and that is deliberate: the bump diff is two lines in `app/build.gradle.kts`, and nothing
the sweep checks — JVM tests, lint, warnings-as-errors, spelling, links, secrets — can be
affected by changing an integer. Every commit a release carries already passed the sweep on its
own PR.

What feature PRs never cover is the **release variant**: R8, resource shrinking, the baseline
profile, the Firebase config guard, signing. A green debug tree can still fail to build, or ship
a broken app, in release. `bundleRelease` succeeding and `jarsigner` verifying is therefore the
gate a release adds, and run 1 performs it every time.

Do not add the sweep back into the release flow, and do not treat its absence as a missing step
to apologize for in a PR body.

## Lesson 040 — native dependencies

Run 1 diffs `gradle/libs.versions.toml` against the previous tag and emits a warning if the
dependency catalog moved. That is a detector, not a verification. If the warning fires and
something native, JNI-backed or reflection-heavy landed,
[Lesson 040](../../../docs/lessons_learned/040-run-the-release-apk-when-a-native-dependency-lands.md)
applies: build and install the release APK from the build sha on an emulator, drive the feature
that dependency serves, and confirm no R8-only crash before the owner uploads. If nothing
native landed, say so explicitly and skip — do not run a device check with nothing to verify.

## Release notes

Run 1 drafts both files into the artifact:

- **GitHub release notes** (technical) — grouped commit log since the previous tag. Only needed
  if the owner wants a curated summary; `tag-release.ps1` defaults to `gh --generate-notes`.
- **Play Console "What's new"** (user-facing) — a placeholder, deliberately not auto-written.
  Short, plain, feature-first bullets, no version numbers, no jargon. Three tight bullets is the
  house style. If no `app/src` changed since the last tag, the draft says so — surface that to
  the owner, because a release with nothing user-facing may not be worth a production rollout.

## Owner calls — binding, read before doing anything

1. **2026-08-28 (issue #158)** — this skill does **not** run or gate on the Play
   technical-quality vitals check. It is still documented as a release requirement in
   `DEFINITION_OF_DONE.md` and `release-signing-and-aab.md` §3; it is deliberately not performed
   here. If a checklist asks for vitals numbers, write
   `N/A — vitals check out of scope for /cut-release, owner call 2026-08-28 (issue #158)`.
2. **2026-09-07** — the release is automated end to end except the Play upload. The workflow
   merges its own bump PR; no approving review is required on it. The sweep does not run on a
   release bump. The tag is still cut only after the upload is confirmed.

## Final verification — exactly these two checks, nothing else

1. The release run's artifact contains `openloop-<version>-<code>.aab` and the run's signature
   step reported `jar verified`.
2. `gh release view <version>` returns the release (live on GitHub).

Report both. Do not add a vitals or quality check as a third gate.

## Prerequisites

Repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`,
`GOOGLE_SERVICES_JSON` for every run, plus `RELEASE_TOKEN` — a credential that can bypass the
branch ruleset (a fine-grained PAT, or a GitHub App token) — for a real run, because that run
pushes a branch and merges its own PR. A run without it fails immediately with a message naming
the secret. A dry run needs no token.

## Manual fallback

`scripts/tag-release.ps1` and `scripts/pre-pr-sweep.ps1` still exist and still work. If the
pipeline is broken and a release genuinely cannot wait, the old sequence is recoverable from
[`docs/play-store/release-signing-and-aab.md`](../../../docs/play-store/release-signing-and-aab.md).
Treat that as an incident, not an option: say plainly that you are working around a broken
pipeline, and fix the pipeline afterwards rather than leaving two paths in use.

## Behavioral rules (non-negotiable)

1. Never cut the tag before the owner confirms the Play upload, regardless of phrasing ("just
   ship it", "do it all", "I trust you"). Nothing in a session can verify that upload.
2. Never type, or ask the owner to type, a `versionCode` or `versionName`. Run 1 reads them.
3. Never derive a build sha from a branch name or a moving `origin/main` — use the sha the
   release run reports, which is the merge commit it built.
4. Never gather or gate on Play vitals numbers — out of scope per the 2026-08-28 owner call.
5. Never attach the `.aab`, an unsigned APK, or any binary to the GitHub release.
   `release-signing-and-aab.md`'s "Never attach" section explains why: a signing-key mismatch
   between the upload key and Play's app-signing key forks the install base permanently. The
   bundle lives in the workflow run's artifacts, which is not the same thing.
6. Never do the release by hand because dispatching is inconvenient. If you cannot dispatch the
   workflow from this session, say so and hand it to the owner.
7. If a stage's precondition does not hold, say so and refuse rather than improvising around it.
