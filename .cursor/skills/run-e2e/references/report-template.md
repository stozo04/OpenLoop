# E2E report template

Write the finished report to `docs/e2e/<timestamp>.md` (timestamp = `yyyy-MM-dd_HHmmss`
from `Get-Date -Format "yyyy-MM-dd_HHmmss"`). Fill every section; keep it honest —
a skipped step or an unverified claim is itself a finding.

```markdown
# OpenLoop E2E run — <yyyy-MM-dd HH:mm>

**Build:** versionName <x.y.z> (versionCode <n>) · debug APK
**Device:** <manufacturer model> · Android <release> (API <sdk>) · <abi> · serial <emulator-XXXX>
**Branch / commit:** <branch> @ <short-sha>
**Driver:** Claude Code `run-e2e` skill
**Logcat:** <path to captured logcat file>

## Verdict

**PASS | FAIL | PARTIAL** — one sentence on the overall state and the single most important thing to act on.

## Flow coverage

| Step | Action | Result | Evidence (logcat line / UI text) |
|------|--------|--------|----------------------------------|
| Launch | cold start to viewfinder | ✅/❌ | |
| Record | capture ~Ns clip | ✅/❌ | |
| Trim tab | <modification made> | ✅/❌ | duration before→after |
| Speed tab | <modification made> | ✅/❌ | current speed before→after |
| Loop tab | <direction chosen> | ✅/❌ | |
| Filter tab | <look chosen> | ✅/❌ | |
| Create/Save | render boomerang | ✅/❌ | `Worker result SUCCESS` ts |
| View loop | gallery playback | ✅/❌ | `ExoPlayerImpl: Init` ts |

## Concerning logs

Paste the `scan-logcat.ps1` count table. Then for each nonzero CRASH/TIMEOUT row,
quote the actual line(s) and the surrounding context.

| Class | Signature | Count |
|-------|-----------|------:|
| ... | ... | ... |

## Findings & issues to research deeper

Number each. For each: what was observed, why it matters, and a concrete next step
or hypothesis. Separate *hard findings* (crash/ANR/failure) from *smells*
(resource churn, slow paths, confusing UX). Link related repo docs/memories.

1. **<title>** — observation → why it matters → next step.

## What could not be verified

Honest list: steps skipped, paths not exercised, things confirmed only via logcat
(not visually, because screenshots are rate-limited), flaky interactions, etc.
```
