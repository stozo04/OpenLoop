---
name: crashlytics-triage
description: >-
  Triages OpenLoop Firebase Crashlytics issues via the Firebase MCP server — prioritize,
  investigate stack traces, map to repo code, and document findings. Use when the user says
  "/crashlytics-triage", "crashlytics:connect", "triage crashlytics", "prioritize crashes",
  "firebase crashes", "top issues", "debug issue <id>", or wants MCP-backed Crashlytics
  investigation instead of only the web console. Requires Firebase Cursor plugin (or
  firebase-tools MCP), CLI login, and Crashlytics enabled in release/debug builds with
  app/google-services.json present locally.
---

# crashlytics-triage — OpenLoop Crashlytics via Firebase MCP

Use the **Firebase MCP server** (`plugin-firebase-firebase` or `npx firebase-tools@latest mcp`)
to pull live Crashlytics data, prioritize issues, investigate samples, and propose fixes.
Do not guess from stack frames alone — fetch issue + event data first.

Official workflow: [Crashlytics AI assistance MCP](https://firebase.google.com/docs/crashlytics/ai-assistance-mcp)

## Ground truth (OpenLoop)

| Field | Value |
|-------|--------|
| Firebase project | `openloop-8c266` (`.firebaserc` default) |
| Android **appId** (required on every MCP call) | `1:95815153197:android:c30254bb713d1e6ae96aa4` |
| Package / applicationId | `io.github.stozo04.openloop` |
| `google-services.json` | `app/google-services.json` (gitignored; plugins apply only when present) |
| Non-fatal reverse failures | `ReverseCrashlytics.kt` — keys like `reverse_outcome`, `video_mime` |
| Codec churn / issue `3a506c4e` | [`docs/lessons_learned/020-*.md`](../../../docs/lessons_learned/020-imported-clips-hdr-codec-and-reverse-failure-recovery.md), [`023-*.md`](../../../docs/lessons_learned/023-media-pipeline-stages-must-count-output-samples.md), [`reverse-video-research.md`](../../../docs/guides/reverse-video-research.md) |

Non-fatals upload on **next app launch**, not instantly.

## Prerequisites (verify before triage)

1. Call `firebase_get_environment` on server `plugin-firebase-firebase`.
   - Expect authenticated user and active project `openloop-8c266`.
   - `firebase.json` is **not** required for Crashlytics-only triage.
2. If MCP tools are missing: enable Firebase plugin in `.cursor/settings.json` or add to `~/.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "firebase": {
      "command": "npx",
      "args": ["-y", "firebase-tools@latest", "mcp"]
    }
  }
}
```

Workaround if Crashlytics tools do not load: add `"--only", "crashlytics"` to `args`.

3. If auth fails: `npx -y firebase-tools@latest login` (same account as Firebase console access).

## Mode A — Full triage (default)

Run when the user invokes `/crashlytics-triage` or asks to prioritize / triage without a specific issue.

### Step 1 — Environment

Call `firebase_get_environment`. Report project, user, and directory. Stop with setup steps if unauthenticated.

### Step 2 — Prioritize (last 7 days unless user specifies a range)

1. Read MCP resource `firebase://guides/crashlytics/reports` (or use bundled knowledge: default window = 7 days, max 90 days; set **both** `intervalStartTime` and `intervalEndTime` for custom ranges).
2. `crashlytics_get_report` with `appId` above, `report: "topIssues"`, `pageSize: 10`.
   - User asked for **crashes only** → filter `issueErrorTypes: ["FATAL"]`.
   - User asked for **non-fatals** → `["NON_FATAL"]`.
   - User asked for **ANRs** → `["ANR"]` (Android only).
3. `crashlytics_get_report` with `report: "topVersions"` (same filters/time).
4. Rank top 5 issues unless the user gave different rules:
   - **a)** Affects the most recent shipped version(s)
   - **b)** Impacted users across variants
   - **c)** Event volume
5. Present issues using this format:

```markdown
1. Issue <full hex id>
   - <title>
   - <subtitle>
   - **Description:** <errorType> · <eventsCount> events · <impactedUsers> users · versions <first>–<last> · <state>
   - [Console](<uri from API if present>)
```

Include **rationale** for ordering. **Ask** whether to deep-dive an issue before editing code.

### Step 3 — Optional dashboard parity

If helpful, also fetch `topAndroidDevices` or `topOperatingSystems` for the highest-priority issue (`filter.issueId`).

## Mode B — Investigate one issue

When the user gives an issue id, console URL, or picks one from Mode A:

1. Read `firebase://guides/crashlytics/investigations`.
2. `crashlytics_get_issue` with `appId` + `issueId` (hex UUID, no `issues/` prefix).
3. `crashlytics_batch_get_events` using `sampleEvent` from the issue response; more samples via `crashlytics_list_events` with the same filters (`issueId`, versions, error types).
4. `crashlytics_list_notes` — resume prior on-call context.
5. Read stack frames in the repo (`VideoReverser`, `OpenLoopViewModel`, `ReverseCrashlytics`, etc.).
6. Cross-check [`docs/lessons_learned/020-imported-clips-hdr-codec-and-reverse-failure-recovery.md`](../../../docs/lessons_learned/020-imported-clips-hdr-codec-and-reverse-failure-recovery.md), [`023-media-pipeline-stages-must-count-output-samples.md`](../../../docs/lessons_learned/023-media-pipeline-stages-must-count-output-samples.md), and [`docs/guides/reverse-video-research.md`](../../../docs/guides/reverse-video-research.md) for known reverse/codec incidents.
7. Produce the investigation plan (do not implement until user confirms):

```markdown
## Cause
<root cause>
- **Fault:** <this repo vs dependency/library>
- **Complexity:** simple | moderately simple | moderately hard | hard | oof, I don't know where to start

## Fix
1. <step>
2. <step>

## Test
1. <manual or automated check>

## Other potential causes
1. ...
```

If the report lacks signal, **say so** — do not invent a fix from a misleading top frame (e.g. `DisplayInfo.createFromParcel` on OOM).

### Documentation actions (when user asks)

| Action | Tool |
|--------|------|
| Add investigation note | `crashlytics_create_note` |
| Close / update state | `crashlytics_update_issue` |

## Mode C — Guided connect

If the environment exposes the `crashlytics:connect` MCP prompt, offer it as an alternative entry point; otherwise Mode A/B above is equivalent.

## MCP tool quick reference

| Goal | Tool |
|------|------|
| Auth / project | `firebase_get_environment` |
| Prioritize | `crashlytics_get_report` (`topIssues`, `topVersions`, …) |
| Issue metadata | `crashlytics_get_issue` |
| Sample events / stacks | `crashlytics_batch_get_events`, `crashlytics_list_events` |
| Notes | `crashlytics_list_notes`, `crashlytics_create_note` |
| Issue state | `crashlytics_update_issue` |

**Always** pass `appId: 1:95815153197:android:c30254bb713d1e6ae96aa4`.

Filter display names (`versionDisplayNames`, `deviceDisplayNames`, `operatingSystemDisplayNames`) must come from prior API `displayName` fields, not invented strings.

## Example prompts (user-facing)

- `/crashlytics-triage` — full prioritize + top 5
- `Fetch topIssues for the last 14 days, fatals only`
- `Debug issue 3a506c4ecc5bfeff0ab2b56d58f6e1d6 — root cause and fix plan`
- `Add a note to issue abc123 summarizing today's investigation`

## What this skill does not do

- Initialize Firebase in the app (already wired in `app/build.gradle.kts`)
- Commit `google-services.json`
- Ship builds or run `adb` (use `reset-storage` only for onboarding DataStore reset)
