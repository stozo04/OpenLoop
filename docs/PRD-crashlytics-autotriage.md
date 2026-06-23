# PRD — Crashlytics auto-triage → GitHub issue → Claude draft PR

**Status:** Draft, awaiting owner sign-off. No code in this PRD is deployed or wired up yet.
**Owner:** Steven Gates · **Firebase project:** `openloop-8c266` · **Repo:** `stozo04/OpenLoop`
**Related:** [`DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md), [`lessons_learned/024-fgs-type-constant-api-gating.md`](lessons_learned/024-fgs-type-constant-api-gating.md) (a Crashlytics-sourced fix — the workflow this PRD describes would have filed it automatically).

> **Experimental dependency.** The Crashlytics MCP tools (via the Firebase MCP server) are flagged **Experimental** by Google — no SLA, may change in backward-incompatible ways. This design treats them as best-effort and degrades gracefully if a tool call fails.

---

## 1. Problem statement

OpenLoop is live in Production and reachable by a very large user base. New fatal crashes surface in Firebase Crashlytics, but turning a crash into a tracked, diagnosed, and fixed issue is entirely manual today: someone has to notice the crash, open the console, read the stacktrace, file a GitHub issue, find the offending code, and write a fix. That latency and manual toil means real crashes sit unaddressed.

We want a crash to **automatically** become a tracked GitHub issue with a Claude-authored root-cause diagnosis, and — when Claude is confident — a **draft** pull request with a first-pass fix, so the owner starts from a diagnosis and a candidate patch instead of a blank page.

## 2. Goals & non-goals

**Goals (first milestone — "Triage + draft PR"):**

- A new *fatal* Crashlytics issue auto-creates exactly one GitHub issue, labeled `crashlytics-auto`, containing crash metadata and a console deep link.
- Claude (via the GitHub Action) pulls the full stacktrace and sample events through the Firebase MCP server, reads the codebase and `docs/lessons_learned/`, and posts a root-cause comment.
- When confidence is high, Claude opens a **draft** PR with a minimal fix on a `feature/crashlytics-<issueId>` branch, linking the issue. When not, it labels the issue `needs-human` and explains why.
- Nothing is ever auto-merged. A human always reviews.

**Non-goals (explicitly out of scope for this milestone):**

- Auto-merging fixes, or treating the draft PR as "Ready for PR" per [`DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md). See §7.
- Acting on non-fatal issues, ANRs, or velocity alerts (deferred to a later milestone).
- Running the full DoD gate (emulator boot + screenshot + release build) on the CI runner — the runner cannot do this cheaply or reliably for Android. The human completes the gate.

## 3. Success criteria

- **Coverage:** ≥ 95% of new fatal issues produce a GitHub issue within ~5 minutes, with no duplicates.
- **Diagnosis quality:** On a hand-labeled sample of the first 15 issues, the owner rates Claude's root-cause comment "useful" (correct or a reasonable lead) ≥ 70% of the time before draft-PR generation is trusted.
- **Safety:** Zero auto-merges; zero non-draft PRs created by the bot; zero issues created for the same Crashlytics issue twice.
- **Cost:** Bounded by `--max-turns` and label-gated triggering; no runaway Action minutes or token spend.

## 4. Architecture

```
Crashlytics (new fatal issue)
        │  Firebase Alerts: crashlytics.newFatalIssue
        ▼
Cloud Function v2  onNewFatalIssuePublished   (functions/index.js)
        │  dedupe → POST /repos/stozo04/OpenLoop/issues  (label: crashlytics-auto)
        ▼
GitHub Issue (#N)  — crash metadata + console deep link + @claude instructions
        │  on: issues [opened], if label == crashlytics-auto
        ▼
GitHub Action  crashlytics-autotriage.yml
        │  - checkout repo
        │  - auth to Google → Firebase MCP can read Crashlytics
        │  - anthropics/claude-code-action@v1 with Firebase MCP config + prompt
        ▼
Claude: crashlytics_get_issue / list_events (stacktrace) → read code + lessons_learned
        ├─ always: post root-cause comment on the issue
        ├─ high confidence: open DRAFT PR (feature/crashlytics-<id>) with minimal fix
        └─ low confidence: label needs-human + explain
        ▼
Human (Steven): run the full DoD gate, then mark ready / merge — or reject
```

### 4.1 Components

| Component | File | Responsibility |
|-----------|------|----------------|
| Crash trigger | `functions/index.js` | `onNewFatalIssuePublished` handler: dedupe, format, create GitHub issue |
| Functions manifest | `functions/package.json` | Node 22 runtime, `firebase-functions` v6 |
| Firebase deploy config | `firebase.json` | New file — declares the functions codebase (`crashlytics-autotriage`) |
| Triage workflow | `.github/workflows/crashlytics-autotriage.yml` | Trigger on labeled issue, auth, run Claude action |
| MCP config | `.github/firebase-mcp.json` | Declares the Firebase MCP server for the action |

### 4.2 Auth & secrets

| Secret / config | Where | Purpose |
|-----------------|-------|---------|
| `GITHUB_TOKEN` (fine-grained PAT or GitHub App token) | Cloud Function secret (Secret Manager) | Function creates issues in `stozo04/OpenLoop` (Issues: read & write) |
| `ANTHROPIC_API_KEY` | Repo Actions secret | Claude Code Action |
| `GCP_SA_KEY` (service-account key JSON) or Workload Identity Federation | Repo Actions secrets | Lets the runner's Firebase MCP read Crashlytics |
| Crashlytics read role | Service account IAM | `roles/firebasecrashlytics.viewer` (add `...writer` only if we later let Claude post Crashlytics notes / close issues) |

For Phase 0 the simple path is a service-account key JSON stored as `GCP_SA_KEY`. The more secure long-term option is **Workload Identity Federation** (no downloadable key); the workflow keeps that as a documented swap.

## 5. Behavior details

- **Dedupe:** before creating an issue, the function searches existing `crashlytics-auto` issues for the Crashlytics issue ID marker (`Crashlytics-Issue-ID: <id>` in the body). If found, it skips instead of creating a duplicate.
- **Trigger scope:** the workflow runs only when an opened issue carries the `crashlytics-auto` label, so human-filed issues never spend tokens.
- **Tool allowlist:** Claude is given the Crashlytics **read** MCP tools plus normal file/git tools. Crashlytics write tools (`crashlytics_update_issue`, `crashlytics_create_note`) are withheld in this milestone.
- **Confidence gate:** the prompt instructs Claude to open a draft PR only when it can point to a specific cause in the code; otherwise comment + `needs-human`.
- **No loops:** the bot never opens issues from the Action, only PRs — so it can't retrigger itself.

## 6. Rollout plan

1. **Phase 0 — issue-only (recommended first):** deploy the function; let it create issues. Manually run the Claude action on a couple of them via `workflow_dispatch` to sanity-check diagnosis quality. No auto draft-PRs yet.
2. **Phase 1 — auto draft-PR:** enable the `issues: [opened]` trigger and draft-PR generation once §3 diagnosis-quality bar is met.
3. **Phase 2 (future):** consider velocity alerts, ANRs, and (separately decided) letting Claude post a Crashlytics note linking the PR.

## 7. The Definition-of-Done tension (decision required)

[`DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md) states the **Production zero-error rule**: a PR is opened only from a fully green state (clean debug + release build, 0 test failures, 0 new lint errors, app run on an emulator with a screenshot). **A CI runner cannot satisfy that gate for an Android app** — no emulator boot + screenshot per crash, cheaply or reliably.

This design resolves the tension by making the bot's output a **draft triage PR**, explicitly *not* "Ready for PR":

- The PR is opened as a **draft**, labeled `crashlytics-auto`, with a body that states plainly which DoD steps were **not** run on CI and includes the manual QA checklist.
- It is a *starting point*. Steven runs the full DoD gate locally and only then marks it ready / merges.

**Decision needed from the owner:** do we formally amend `DEFINITION_OF_DONE.md` to recognize "automated triage draft" as a distinct, non-mergeable state (so the bot isn't seen as violating the zero-error rule)? Recommended: yes — a short subsection that says triage drafts are exempt *because* they can never be merged without a human clearing the full gate.

## 8. Risks & mitigations

| Risk | Mitigation |
|------|-----------|
| Crashlytics MCP is Experimental and may break | Degrade gracefully; function-created issue still lands even if MCP calls fail |
| Issue spam / token burn on crash storms | Gate on `newFatalIssue` only; dedupe; `--max-turns` cap; label-scoped trigger |
| Unfixable crashes (OEM, OOM, third-party SDK) | Confidence gate → comment + `needs-human` instead of a bad patch |
| Bot opens a low-quality patch that looks authoritative | Always **draft**; never auto-merge; human owns the DoD gate |
| New `firebase.json` changes `firebase deploy` behavior | Function isolated in its own codebase name; documented in setup checklist |
| Secret leakage | Least-privilege IAM; all tokens in Secret Manager / Actions secrets; WIF available as a keyless upgrade |

## 9. Open questions

1. Amend `DEFINITION_OF_DONE.md` for the triage-draft state (§7)? Recommend yes.
2. GitHub auth for the function: fine-grained PAT (simple) vs. a GitHub App (cleaner attribution, more setup). Recommend PAT for v1.
3. Should Claude post a Crashlytics note linking the PR (needs the writer role + write tools)? Default: no, in v1.
4. Region for the function (`us-central1` default) and whether to also handle `onNewNonfatalIssuePublished` later.
5. Confidence threshold wording — tune the prompt after the first batch of real issues.

## 10. Setup checklist (owner actions, after sign-off)

- [ ] Decide §7 and §9 open questions.
- [ ] Create the GitHub label `crashlytics-auto` (and `needs-human`).
- [ ] Create a fine-grained PAT (Issues: R/W on `stozo04/OpenLoop`) → store as Cloud Function secret `GITHUB_TOKEN`.
- [ ] Add `ANTHROPIC_API_KEY` to repo Actions secrets.
- [ ] Create a service account with `roles/firebasecrashlytics.viewer`; store its key as the repo secret `GCP_SA_KEY` (or configure Workload Identity Federation).
- [ ] Review the new `firebase.json` and `functions/`; `firebase deploy --only functions:crashlytics-autotriage`.
- [ ] Run the Claude workflow via `workflow_dispatch` on a test issue (Phase 0) before enabling the auto trigger.
