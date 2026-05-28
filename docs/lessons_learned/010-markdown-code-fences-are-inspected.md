# Lesson 010 — Markdown code fences are parsed by the IDE; keep snippets valid

## What went wrong

Lesson 008 contained a ` ```kotlin ` fence with two constructs that are **not legal Kotlin**:

```
fun `...`() = runTest(mainDispatcherRule.testDispatcher) {
    ...
    advanceUntilIdle()
}
```

1. `` `...` `` — a backtick (escaped) identifier containing dots. Kotlin forbids `.`, `;`, `:`,
   `/`, `\`, `[`, `]`, `<`, `>` inside escaped identifiers, even as a placeholder name.
2. `...` on its own line — three dots are not a valid Kotlin expression (it parses as three `.`
   tokens), so the parser errors.

Android Studio / IntelliJ **injects the declared language of every Markdown code fence** and runs
that language's parser and annotators on the contents. So invalid snippets show up as hard
"General Errors" in batch inspection — the same severity as a real broken `.kt` file — even though
they're only documentation.

## Pattern

Keep fenced snippets syntactically valid for the language you tag them with:

- **Placeholder names:** use spaces, not dots, in backtick test names — `` `auto-stop fires after the delay` ``, never `` `...` ``.
- **Elision:** never leave a bare `...` line. Use a real comment for the language — `// ...` (Kotlin/Java/JS), `# ...` (Python/TOML/shell).
- **No `import` / `package` in Kotlin fences:** a ` ```kotlin ` block is injected as a *code
  fragment*, not a whole file, and fragments forbid these directives — you get *"Package directive
  and imports are forbidden in code fragments."* Name the import in prose instead (e.g. "import
  `androidx.lifecycle.compose.collectAsStateWithLifecycle`"), or comment it (`// import …`).
- **Genuinely non-compilable illustrations:** if a snippet can't be made valid (pseudocode,
  partial grammar), drop the language tag — a plain ``` ``` ``` fence or ` ```text ` disables
  injection, so the inspector leaves it alone (you lose syntax highlighting, which is the right
  trade for pseudocode).

This is why the rule "make the doc snippet parse" matters: the doc is held to the same bar as code.

## Detection checklist

- Dotted backtick identifiers:
  ```
  grep -rnE "`[^`]*\.[^`]*`" docs --include="*.md"
  ```
  (Ignore inline file references like `MainActivity.kt`; flag backtick identifiers used *as code*,
  especially after `fun `/`val `/`class `.)
- Bare ellipsis lines inside fences:
  ```
  grep -rnE "^\s*\.\.\.\s*$" docs --include="*.md"
  ```
- `import` / `package` directives inside fences:
  ```
  grep -rnE "^\s*(import|package)\s" docs --include="*.md"
  ```
- After editing any doc with code fences, confirm the IDE shows zero "General Errors" for that file.

## Reference

- [JetBrains — Language injections](https://www.jetbrains.com/help/idea/using-language-injections.html):
  fenced code in Markdown is injected and inspected.
- Related: [Lesson 009 — TOML inline tables single-line](./009-toml-inline-tables-single-line.md) (a TOML instance of the same root cause).
- Surfaced fixing the doc inspection errors flagged on lessons 002 and 008.
