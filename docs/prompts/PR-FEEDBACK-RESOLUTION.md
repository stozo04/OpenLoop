# PR Feedback Resolution — Reusable Session Prompt

Copy everything below the line into a fresh Claude session with the OpenLoop folder mounted. Replace `#XX` with your PR number.

---

## Session Prompt — Address PR Review Feedback & Re-Review

You are working on **OpenLoop** — an open-source Android camera app (Kotlin/Jetpack Compose) for creating speed-controlled video loops. Repo: `stozo04/OpenLoop`. Owner: Steven Gates (@stozo04).

## Critical Rule — Do Not Trust Your Training Data

Your knowledge cutoff could be a year old. **Do not assume** you know the current version of any Google standard, Android API behavior, Jetpack library pattern, testing framework convention, or Play Store requirement. Before making any claim about how something works or what Google recommends, **web-search `developer.android.com` first**. This applies to everything — architecture patterns, Compose APIs, DataStore usage, CameraX, coroutines, permissions, accessibility, Play Store requirements, and any external package or library. If you catch yourself writing "Google recommends X" without having searched for it in this session, stop and search.

## Context

PR #XX received an automated standards review from our PR Reviewer skill. The review posted feedback directly on the PR as a comment with specific PASS/FAIL/WARNING findings, each citing Google documentation.

Your job is to **fix every issue** the reviewer found, then **prove the fixes are clean** by running a fresh review.

## Before Writing Any Code — Read These Files

These are your ground truth. They contain decisions, conventions, and constraints that override any assumptions:

1. `CLAUDE.md` — Operating instructions, architecture snapshot, reference doc pointers
2. `docs/PRD-mission-control.md` — Authoritative component specs, decision log (check before changing patterns)
3. `docs/ANDROID_STANDARDS.md` — Google best practices with official doc links
4. `docs/TEST_COVERAGE.md` — Test directory structure, frameworks, coroutine testing, current inventory

Also check `docs/active/` for the feature's IMPLEMENTATION.md if one exists.

**Read all of these before touching any code.**

## Phase 1: Read the PR Review Feedback

1. Go to the PR on `stozo04/OpenLoop` (use GitHub tools — `pull_request_read` with method `get_comments`)
2. Find the automated review comment (from Claude, titled "PR Review — Google Android Standards Compliance")
3. Read every FAIL, WARNING, and RECOMMENDATION carefully

## Phase 2: Web-Search Before Fixing

For each FAIL and WARNING, **web-search the cited Google documentation URL** to confirm the finding is still valid and to understand the correct fix. Do not assume the reviewer was right — verify independently. Standards change. If a finding turns out to be stale or incorrect, note that in your response.

## Phase 3: Fix Every Issue

Address each FAIL and WARNING:

- For each fix, explain **what** you're changing, **why**, and **which Google standard** it satisfies
- Make the actual code changes on the PR's branch
- If a fix requires new tests, add them (follow conventions in `docs/TEST_COVERAGE.md`)
- If a fix conflicts with a decision in `docs/PRD-mission-control.md` Decision Log, flag the tension — don't silently override

After all fixes are made, **commit and push** to the PR's branch on GitHub.

## Phase 4: Post a Response Comment

After pushing, post a comment on the PR (using GitHub `add_issue_comment`) documenting what you fixed:

```markdown
## PR Review Response — Fixes Applied

**Date:** [today]
**Commit:** [sha]

### Issues Addressed

For each FAIL/WARNING from the review:

- **[Category] [Original finding]**
  - **Action taken:** [what was changed]
  - **File(s):** [file:line]
  - **Google standard verified via:** [URL you searched]
  - **Status:** RESOLVED / ACKNOWLEDGED / DISPUTED (with reasoning)
```

## Phase 5: Kick Off a Fresh Review

After posting your response, run the PR Reviewer skill against the same PR. The skill lives at `.claude/skills/pr-reviewer/`. Follow its 5-phase process:

1. Bootstrap — read project context files
2. Research — web-search Google standards (fresh, not cached)
3. Identify — target the PR
4. Review — evaluate all code against the 11-category, 75+ item checklist
5. Post — write the structured PASS/FAIL/WARNING report as a new comment on the PR

The goal: **zero FAILs** on the re-review. If any remain, fix them and repeat until clean.

## Behavioral Rules

- **Web-search before every claim.** You do not know what Google's current guidance says until you search for it.
- **Show your work.** Every fix includes the reasoning and the Google doc citation.
- **Be specific.** File names, line numbers, code snippets. No vague statements.
- **Respect the Decision Log.** Check `docs/PRD-mission-control.md` before overriding any existing pattern.
- **Tests matter.** If a fix changes behavior, update or add tests. Check `docs/TEST_COVERAGE.md` for conventions.
- **One branch, clean commits.** All work happens on the PR's branch. Push via GitHub API if local git has issues.
