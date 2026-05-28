# Lesson 009 — TOML inline tables must be on a single line

## What went wrong

Lesson 002's `gradle/libs.versions.toml` example was written as a **multi-line** inline table:

```
androidx-lifecycle-runtime-compose = {
    group = "androidx.lifecycle",
    name = "lifecycle-runtime-compose",
    version.ref = "lifecycleKtx"
}
```

The TOML spec requires inline tables (`{ ... }`) to be declared on a **single line** — they may
not span line breaks. The IDE injects the language of every Markdown code fence and runs that
language's parser, so the ` ```toml ` block above surfaced as a hard "General Error" (syntax) in
batch inspection. The same text in a real `libs.versions.toml` would fail to parse and break the
Gradle build.

## Pattern

Write inline tables on one line:

```toml
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleKtx" }
```

If a single line gets unwieldy, use a standard (non-inline) table with its own header instead —
those *can* span multiple lines:

```toml
[libraries.androidx-lifecycle-runtime-compose]
group = "androidx.lifecycle"
name = "lifecycle-runtime-compose"
version.ref = "lifecycleKtx"
```

This applies to real `.toml` files **and** to ` ```toml ` snippets inside Markdown docs — both are
parsed.

## Detection checklist

- Grep for an inline table that opens at end-of-line (the multi-line smell):
  ```
  grep -rn "= {$" --include="*.toml" --include="*.md" .
  ```
  Each hit should be collapsed to one line or converted to a `[header]` table.
- After editing any `.toml` (or a ` ```toml ` fence), confirm the IDE shows zero syntax errors.

## Reference

- [TOML spec — Inline Tables](https://toml.io/en/v1.0.0#inline-table): "Inline tables are intended
  to appear on a single line."
- Related: [Lesson 010 — Markdown code fences are inspected](./010-markdown-code-fences-are-inspected.md) (the IDE parses fenced code).
- Surfaced fixing the doc inspection errors flagged on lessons 002 and 008.
