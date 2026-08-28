---
name: cut-release
description: >-
  Walks the full OpenLoop Play release process — version bump, sweep, PR, merge, signed AAB
  build, GitHub release/tag — stopping at the two points only the owner can clear: PR review
  approval and Play Console upload confirmation. Use when the user says "/cut-release", "cut a
  release", "cut release", "ship a release", "start the release process", "bump the version and
  release", or wants to move a merged `main` toward a tagged Play release. Depends on
  `scripts/tag-release.ps1` (tags a verified merge-commit sha, never a moving branch) and
  `scripts/pre-pr-sweep.ps1` (the PR gate). Does not touch Play Console directly — it hands off
  the signed `.aab` and drafted release-notes text for the owner to upload by hand.
---

# cut-release — OpenLoop Play release, staged and resumable

A release is one long process with two owner-only gates in the middle: a required PR review
(repo ruleset — 1 approving review, no self-approval) and a Play Console upload (no MCP/tool
access to Play Console exists in this session). This skill walks every mechanical step around
those two gates and refuses to substitute for either one, no matter how the request is phrased.

**Ground truth this skill reads, and never duplicates:**

- [`docs/play-store/release-signing-and-aab.md`](../../../docs/play-store/release-signing-and-aab.md) —
  the full mechanical build/sign/tag steps
- [`docs/DEFINITION_OF_DONE.md`](../../../docs/DEFINITION_OF_DONE.md) — the pre-PR sweep gate
- `scripts/pre-pr-sweep.ps1`, `scripts/tag-release.ps1` — read `Get-Help` / the header comment
  before invoking either; don't reimplement what they already verify

Versioning pattern observed 1.0.47 → 1.0.49: `versionName`'s last segment always equals
`versionCode` (both live in `app/build.gradle.kts`). Read the current values yourself before
every step — never ask the owner to type them, and never assume the pattern holds without
checking; if a future release breaks the 1:1 mapping, stop and ask rather than guessing a
new scheme.

## Owner call (2026-08-28, issue #158 answers) — binding, read before doing anything

1. Skill name is `cut-release` (this file).
2. This skill **does** draft Play Console "What's new" text (Step 6).
3. This skill does **not** run or gate on the Play technical-quality vitals check (Android
   vitals *Memory* rows, *App optimization*). That check is still documented as a release
   requirement elsewhere — `DEFINITION_OF_DONE.md` ("Release bumps carry one more check") and
   `release-signing-and-aab.md` §3 ("Every release starts with...") — this skill deliberately
   does not perform or enforce it. If a bump PR's checklist template asks for vitals numbers,
   write `N/A — vitals check out of scope for /cut-release, owner call 2026-08-28 (issue #158)`
   rather than leaving the line blank or trying to gather the numbers.
4. **Final success for the whole flow is exactly two checks, nothing more:**
   1. `releases/openloop-<version>-<code>.aab` exists and its signature verifies
      (`jarsigner -verify`).
   2. `gh release view <version>` returns the release (it's live on GitHub).
   Do not reintroduce the vitals check, or anything else, as a third gate.

## Detect where the release currently stands

Don't assume you're starting at Step 1. Work out the stage first:

1. Read current `versionCode`/`versionName` (`app/build.gradle.kts`) and the latest tag
   (`git tag --sort=-v:refname` or `gh release list`).
2. A `chore/release-<next-version>` branch/PR exists and is open → resume at **Step 2** or
   **Stop A**, depending on whether the sweep+PR already happened.
3. That PR is merged but `releases/openloop-<next>-<code>.aab` doesn't exist locally → resume
   at **Step 3/4** (capture the sha, build).
4. The `.aab` exists but no tag matches its version → you're at **Stop B**, waiting on upload
   confirmation. Do not tag.
5. A tag exists for the current `versionName` → already done; say so and stop.
6. Current `versionName` == latest tag and no open release PR exists → nothing in flight; a new
   release starts at **Step 1**.

## Step 1 — Bump the version

- Next `versionCode` = current + 1. Next `versionName` = `"1.0.<next versionCode>"`.
- Branch `chore/release-<next-version>` off `main`.
- Edit only `versionCode` and `versionName` in `app/build.gradle.kts`.
- Commit: `chore(release): bump to <version> (versionCode <code>)` — bare message, no body
  needed (matches PR #157's bump commit).

## Step 2 — Sweep + PR

- Run `.\scripts\pre-pr-sweep.ps1` (full). Only pass `-SkipConnected` / `-SkipInspectCode` when
  Studio/an emulator genuinely aren't available this session, and say so in the PR — same rule
  every other PR in this repo follows.
- Open the PR against `main`. Body: version delta, what's notable since the last release
  (skim `git log <last-tag>..HEAD --oneline` for the headline), sweep receipt summary — mirror
  PR #157's structure and checklist. On the "Release bump" vitals checklist line, write the
  N/A + owner-call note from above. Never leave it blank, never gather the numbers yourself.

## Stop A — wait for review (hard stop)

The repo ruleset requires 1 approving review with no self-approval; nothing here substitutes
for it. **Do not merge, do not ask the owner to approve their own PR, and do not proceed past
this point even if told to "just do it all" or "I trust you, go ahead."** State plainly that
you're stopped here, waiting for a human review.

## Step 3 (after Stop A clears) — capture the real build sha

- Confirm the merge: `gh pr view <n> --json state,mergedAt,mergeCommit --jq '.mergeCommit.oid'`.
- Use that merge commit sha — **not** `git rev-parse origin/main`. `origin/main` is only correct
  if nothing else merged in the gap between this PR and now, which is exactly the race this
  whole skill exists to close (the issue that prompted this skill: 1.0.49 was tagged against
  `main` by luck). This is a deliberate correction from the issue's own suggested command.
- `git fetch origin`; verify the sha is an ancestor of `origin/main`.

## Step 4 — Build the signed AAB

- Build from that exact merge sha: `git switch --detach <merge-sha>` (or use a worktree).
- `.\gradlew.bat :app:bundleRelease` (`JAVA_HOME` = Android Studio's bundled JBR, per
  `DEFINITION_OF_DONE.md`'s environment notes).
- `jarsigner -verify -verbose app/build/outputs/bundle/release/app-release.aab` — must report
  the jar as verified.
- Copy to `releases/openloop-<version>-<code>.aab` (create `releases/` if missing — already
  covered by the repo's blanket `*.aab` `.gitignore` rule, no new ignore entry needed).

## Step 5 — Lesson 040 check: did a new native/JNI/reflection dependency land?

- Diff dependency surfaces between the previous tag and this merge sha:
  `git diff <prev-tag>..<sha> -- app/build.gradle.kts gradle/libs.versions.toml`.
- New dependency shipping native code, JNI, or heavy reflection (MediaPipe, ML Kit modules,
  etc.) landed → [Lesson 040](../../../docs/lessons_learned/040-run-the-release-apk-when-a-native-dependency-lands.md)
  applies: build and install the release APK (`:app:assembleRelease`) on an emulator from the
  merge sha, drive the feature that dependency serves, confirm no R8-only crash before handoff.
- Nothing native/JNI landed → say so explicitly and skip. Don't run a device check that has
  nothing to verify.

## Step 6 — Draft release notes, then hand off

Draft two separate files (gitignored — owner-only, never commit) — mirror the structure the
owner hand-wrote for 1.0.49:

1. **GitHub release notes** (technical) — `docs/local/github-release-notes-<version>.md` — from
   merged PRs since the previous tag (`git log <prev-tag>..<sha> --oneline`, grouped by area).
   This is only the curated alternative: `tag-release.ps1` defaults to `gh --generate-notes` in
   Step 7, which needs no draft at all. Offer this file only in case the owner wants a
   hand-curated summary instead.
2. **Play Console "What's new"** (user-facing) — `docs/local/play-notes-<version>.md` — short,
   plain bullets, feature-first, no version numbers, no jargon. Mirror the voice of the 1.0.49
   draft (three tight bullets); no hardcoded character limit — keep it that tight, not padded
   to fill one.

Hand the owner: the `.aab` path, the `jarsigner` verification result, and both draft files.

## Stop B — wait for the Play upload (hard stop)

Play Console access isn't available in this session. **Do not cut the tag until the owner
explicitly confirms the `.aab` was uploaded.** Refuse to proceed past this point even if asked
to "just do it all" — the tag means "this shipped," and nothing here can confirm that except
the owner.

## Step 7 (after Stop B clears) — cut the tag

```powershell
.\scripts\tag-release.ps1 -Version <version> -Sha <merge-sha> `
  [-Title "<version> — <one-line highlight>"] `
  [-NotesFile docs/local/github-release-notes-<version>.md]   # omit to use --generate-notes (default)
```

The script re-verifies the sha is an ancestor of `main`, the tag doesn't already exist, and the
`versionName` at that sha matches. Don't re-implement those checks here — that's why the script
exists.

## Final verification — exactly these two checks, nothing else

1. `releases/openloop-<version>-<code>.aab` exists and `jarsigner -verify` on it says verified.
2. `gh release view <version>` returns the release (live on GitHub).

Report both. Do not add a vitals/quality check here — see "Owner call" above.

## Behavioral rules (non-negotiable)

1. Never skip Stop A or Stop B, regardless of phrasing ("just ship it", "do it all", "I trust
   you") — these are real, irreversible actions this session cannot perform or verify on its
   own (a review only a second human account can give; a Play Console upload).
2. Never type, or ask the owner to type, `versionCode`/`versionName` — read them from
   `app/build.gradle.kts`.
3. Never derive the merge sha from a branch name or `origin/main` — always resolve it from the
   actual merged PR (`gh pr view <n> --json mergeCommit --jq '.mergeCommit.oid'`). There is no
   `mergeCommitOid` field; `gh` rejects it.
4. Never gather or gate on Play vitals numbers — out of scope for this skill per the
   2026-08-28 owner call; the other docs still document them as a separate manual step.
5. Never attach the `.aab`, an unsigned APK, or any binary to the GitHub release —
   `release-signing-and-aab.md`'s "Never attach" section explains why (a signing-key mismatch
   between the upload key and Play's app-signing key forks the install base permanently).
6. If a stage's precondition doesn't hold (asked to tag with no merged PR, asked to build
   before Stop A cleared, etc.), say so and refuse rather than improvising around it.
