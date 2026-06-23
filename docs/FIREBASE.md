# Firebase — Crashlytics auto-triage runbook

Operational notes for the Crashlytics → GitHub → Claude auto-triage system. Design and rationale live in [`PRD-crashlytics-autotriage.md`](PRD-crashlytics-autotriage.md); this file is the day-to-day "what is it and how do I fix it" reference.

**Firebase project:** `openloop-8c266` · **Repo:** `stozo04/OpenLoop` · **Plan:** Blaze (required for the alert function)
**Project Console:** <https://console.firebase.google.com/project/openloop-8c266/overview>

---

## ⚠️ FIRST THING TO CHECK IF IT STOPS WORKING: the GitHub token expires

The `GITHUB_TOKEN` secret the Cloud Function uses to file GitHub issues is a **fine-grained Personal Access Token created with a 1-year expiration** (created June 2026 → **expires around June 2027** — confirm the exact date at <https://github.com/settings/personal-access-tokens>).

**When that token expires, crashes will silently stop becoming GitHub issues.** No crash, no error popup — the function just starts getting `401 Unauthorized` from GitHub and logs it. So if auto-triage goes quiet, **check the token first.**

### How to renew the token (2 minutes)

1. <https://github.com/settings/personal-access-tokens> → create a new fine-grained token.
   - Resource owner `stozo04`, repository **OpenLoop** only, permission **Issues: Read and write**.
2. Update the secret (paste the new token at the hidden prompt — the argument is the *name*, not the value):

   ```
   firebase functions:secrets:set GITHUB_TOKEN
   ```

3. Re-deploy so the function binds the new secret version:

   ```
   firebase deploy --only functions:crashlytics-autotriage
   ```

> Never paste a token into a terminal command, a file, or a chat. Only into the hidden `Enter a value for GITHUB_TOKEN:` prompt. If a token is ever exposed, revoke it immediately and issue a new one.

---

## What this system is

1. A new **fatal** Crashlytics issue fires a Firebase Alert.
2. The Cloud Function `crashlyticsToGithub` (`functions/index.js`, trigger `onNewFatalIssuePublished`) creates one GitHub issue labeled `crashlytics-auto`.
3. The GitHub Action `.github/workflows/crashlytics-autotriage.yml` wakes Claude, which reads the crash via the Firebase MCP server, comments a root cause, and opens a **draft** PR when confident. Nothing is auto-merged.

## Files

| File | Role |
|------|------|
| `functions/index.js` | The alert listener that files the GitHub issue |
| `functions/package.json` | Node 22 runtime + `firebase-functions` v6 |
| `firebase.json` | Declares the function under codebase `crashlytics-autotriage` |
| `.github/workflows/crashlytics-autotriage.yml` | The triage workflow (runs on GitHub) |
| `.github/firebase-mcp.json` | Firebase MCP server config for the workflow |

## Secrets & access (where each one lives)

| Name | Lives in | Purpose | Expires? |
|------|----------|---------|----------|
| `GITHUB_TOKEN` | Firebase (Secret Manager) | Function files GitHub issues | **Yes — ~June 2027** (see above) |
| `ANTHROPIC_API_KEY` | GitHub repo Actions secrets | Runs Claude | No (unless rotated) |
| `GCP_SA_KEY` | GitHub repo Actions secrets | Lets the workflow read Crashlytics | No expiry, but key can be rotated |
| Service account `gh-crashlytics-reader` | Google Cloud IAM | Holds role `Firebase Crashlytics Viewer` | — |

## Common commands

```
firebase functions:list                                   # is the function deployed?
firebase deploy --only functions:crashlytics-autotriage   # (re)deploy
firebase functions:secrets:set GITHUB_TOKEN               # set/rotate the token (paste at hidden prompt)
firebase functions:secrets:access GITHUB_TOKEN            # reveal the stored value (handy for .secret.local)
```

To read function logs (e.g. to see a `401` from an expired token): Firebase Console → Functions → `crashlyticsToGithub` → Logs, or Google Cloud Console → Logging.

## Testing locally — fake a crash end-to-end (`functions:shell`)

You can exercise the whole chain (function → GitHub issue → workflow → Claude) without waiting
for a real crash, using the Firebase **functions shell**. It runs the real function code locally
but **really** calls GitHub, so it creates a real issue that really triggers the workflow.

**One-time setup**

1. The shell needs `firebase-admin` installed:

   ```
   cd functions
   npm install --save firebase-admin
   ```

2. Create `functions/.secret.local` (gitignored — never commit) holding the token the function
   uses, so `GITHUB_TOKEN.value()` resolves locally:

   ```
   GITHUB_TOKEN=<your fine-grained PAT>
   ```

   Retrieve the value if you don't have it saved: `firebase functions:secrets:access GITHUB_TOKEN`

**Fire a fake crash**

```
cd functions
firebase functions:shell
```

At the `firebase >` prompt, invoke the function. The object you pass becomes the event's `data`,
so the issue must sit at `payload.issue` — **no outer `data:` wrapper** — and use **plain ASCII**:

```js
crashlyticsToGithub({payload:{issue:{id:'<crashlytics-issue-id>',title:'Fake crash - e2e test',subtitle:'NullPointerException',appVersion:'1.2.3'}}})
```

Expected log line: `Created GitHub issue #NN for Crashlytics <id>`. Type `.exit` to leave the shell.

**Gotchas (learned the hard way)**

- **Payload shape:** pass `{payload:{issue:{...}}}`. Wrapping it as `{data:{payload:...}}` makes the
  id read as `unknown` (the shell already nests your argument under `event.data`).
- **Plain ASCII only:** an em-dash (`—`) in a value throws `SyntaxError ... in JSON`. Use `-`.
- **`firebase-admin` required:** the emulator refuses to load functions without it.
- **ADC warning is expected:** the shell uses your local Application Default Credentials and hits
  **production** GitHub — fine for testing, but it creates real issues. Close the test issues after.
- **Dedupe 422:** currently logs `Dedupe search failed (422); proceeding to create.` — non-fatal
  (the issue still gets created) but duplicate-suppression isn't working via this path yet; revisit.

## Troubleshooting — in priority order

1. **No new GitHub issues from crashes?** → **Token expired** (most likely). Renew it (top of this doc). Then check the function logs for `401`.
2. **Function not firing at all?** → Confirm it's deployed (`firebase functions:list`) and the project is still on the **Blaze** plan.
3. **Issue created but no Claude comment / draft PR?** → Check the **Actions** tab. Likely a missing/expired `ANTHROPIC_API_KEY`, the Claude GitHub App was removed, or `GCP_SA_KEY` lost its Crashlytics access.
4. **Claude can't read the crash details?** → The Crashlytics MCP tools are **Experimental** and can change; also verify the service account still has `Firebase Crashlytics Viewer`.
5. **Duplicate issues for one crash?** → The function dedupes on the `Crashlytics-Issue-ID:` marker; if the issue body format changed, the dedupe search can miss.

## Notes

- Crashlytics MCP tooling is **Experimental** at Google (no SLA). The function is built so a crash still becomes a GitHub issue even if the MCP/detective half breaks.
- The bot only ever opens **draft** PRs and never merges — a human always reviews.
