# Lesson 007 — A standards doc and the code in the same PR must agree

## What went wrong

PR #5 introduced `docs/ANDROID_STANDARDS.md` which explicitly recommends `collectAsStateWithLifecycle()`. The same PR's `MainActivity.kt` used `collectAsState()`.

Shipping a standards doc that contradicts code in the same PR teaches every future reader (human or automated) that the doc is aspirational — guidelines to glance at, not rules to enforce. That kills the doc's value as a forcing function before it has a chance to do any work.

## Pattern

- Before opening a PR that adds or updates a standards/conventions document, **scan the working tree for violations of every new rule**. Either fix them in the same PR or call them out explicitly as known exceptions with a tracking issue / lesson file.
- For mechanical rules (specific imports, specific API calls), a `git grep` pass works. Example:
  - Standards doc says "use `collectAsStateWithLifecycle()`" → grep for `collectAsState(` and fix every hit.
  - Standards doc says "no `runBlocking` in app code" → grep for `runBlocking` and fix every hit.
- For architectural rules (e.g. "ViewModel must not depend on Context"), do a manual pass over the touched files.
- Self-review checklist before opening the PR: for each rule added to the standards doc, what evidence proves it's followed in this PR? If none — the rule is either unenforced (so the doc is fiction) or violated (so the PR is broken).

## Detection checklist

After any change to `docs/ANDROID_STANDARDS.md` or other guideline docs:

- For each rule that mentions a specific Kotlin/Compose API, grep the codebase for the forbidden alternative. Zero hits or all-explained-as-exceptions.
- Reviewer test (do this before merge): pick three rules from the doc at random, search the code, confirm compliance.
- If a violation is intentional and can't be fixed in this PR, add a lesson file under `docs/lessons_learned/` documenting the exception and the follow-up.

## Reference

- Meta-lesson from PR #5 FAIL #2 and Recommendation R4. The reviewer specifically called out: "The standards doc this PR introduces says to use `collectAsStateWithLifecycle()`, but the code in the same PR uses `collectAsState()`. Aligning this prevents the standards doc from being treated as aspirational rather than enforced."
