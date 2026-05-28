# Lessons Learned

Distilled rules from past code reviews and bugs. **Every Claude Code session must read this folder at startup** — see `CLAUDE.md` for the mandate.

Each lesson follows the same shape:

- **What went wrong** — the specific mistake, with file/line where useful.
- **Pattern** — the rule going forward, with code snippets where mechanical.
- **Detection checklist** — what to grep / look for to catch the same mistake again.
- **Reference** — Google doc URL and the originating PR.

## Index

| # | Lesson | Origin |
|---|--------|--------|
| 001 | [Compose `Color()` expects exactly 8 hex digits](./001-compose-color-literal-format.md) | PR #5 |
| 002 | [Use `collectAsStateWithLifecycle()` for Flow collection in Compose](./002-lifecycle-aware-flow-collection.md) | PR #5 |
| 003 | [Wrap DataStore writes in try-catch(IOException)](./003-datastore-write-ioexception.md) | PR #5 |
| 004 | [Never pass Context to ViewModel methods; extract a Repository](./004-viewmodel-no-context-parameters.md) | PR #5 |
| 005 | [Track current Play Store target API level requirements](./005-play-store-target-api-level.md) | PR #5 |
| 006 | [Always show permission rationale before re-requesting](./006-permission-rationale-flow.md) | PR #5 |
| 007 | [A standards doc and the code in the same PR must agree](./007-standards-doc-must-match-code.md) | PR #5 |

## Adding a new lesson

When a PR review surfaces a new pattern worth preserving:

1. Number the file as the next sequential ID (zero-padded to 3 digits).
2. Use a kebab-case slug derived from the rule.
3. Add an entry to the index above.
4. Commit the lesson alongside the fix it documents.

A lesson is only worth writing down if it would have prevented the original mistake — abstract advice doesn't qualify. Be specific. Cite file paths, grep patterns, and the exact Google doc URL.
