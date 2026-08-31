# Lesson 041 — Deleting a tracked asset is a docs change: every link to it dies with the file, and the CI gate reports it as `Status: 400`

> Origin: [PR #161](https://github.com/stozo04/OpenLoop/pull/161), 2026-08-30. A commit named
> "clean up" removed `docs/play-store/main-image.png` along with a large batch of skill files. The
> image was still linked from three tracked Markdown files, so the CI text gates went red on a PR
> whose subject had nothing to do with the Play listing.

## What went wrong

`git show --stat` on the commit is 200-odd added files and one line that is easy to miss:

```text
docs/play-store/main-image.png | Bin 989956 -> 0 bytes
```

Nothing in the commit touched Markdown, so the deletion read as harmless. It was not: the feature
graphic is linked from `README.md`, `docs/play-store/README.md` and `docs/play-store/store-listing.md`,
and those links are a **hard** gate (sweep gate 6c, mirrored by the `markdown-link-check` step in
`.github/workflows/static-analysis.yml`). CI reported:

```text
ERROR: 1 dead link found in README.md !
[✖] docs/play-store/main-image.png → Status: 400
```

Two things made that harder to read than it should have been:

1. **`Status: 400` is not an HTTP response.** For a relative link `markdown-link-check` returns
   the same shape it uses for URLs, so a missing *local file* looks like a *network* failure. It
   costs a round of chasing proxies and rate limits before checking whether the path exists.
2. **The step exits after the loop, so it masks the steps behind it.** `cspell` and the JSON /
   dictionary check never ran on that push — a "the links are fixed now" push can still come back
   red on the next gate. Re-run the whole sweep locally, not just the check that failed.

The fix was `git checkout <commit>^ -- docs/play-store/main-image.png`, not repointing the links:
`Feature-logo.png` is a different image (1794 × 876, and no doc references it), and the docs that
link the graphic are release checklists that treat it as a deliverable.

## Pattern

- **Before deleting or renaming any tracked non-code file, grep the docs for its name.** One
  command, and it is the whole lesson:

  ```bash
  git grep -n "main-image.png" -- '*.md'
  ```

  If it has references, the deletion is a docs change too: update the links in the same commit or
  do not delete the file.
- **A bulk commit hides single-file deletions.** Read `git show --stat <sha> | grep " 0 bytes"`
  (or `git log --diff-filter=D`) before pushing anything with a large file count. "Clean up" and
  "sync skills" commits are where this class of break lives.
- **Read a dead relative link as a missing file first.** Check `ls` on the path before assuming
  the checker had a network problem.

## Detection checklist

- `scripts/pre-pr-sweep.ps1` gate 6c is the local backstop and catches this before the push — the
  failure only reaches CI when the sweep was run before the deletion, or skipped.
- When CI is red, read it without leaving the terminal:

  ```bash
  gh pr checks <number>            # which check failed, with the run URL
  gh run view <run-id> --log-failed  # the failing step's log
  ```

- After fixing the reported step, run the full sweep — the failing step short-circuits the ones
  after it, so the log is not a complete list of what is broken.
- `git ls-files docs/play-store/` versus the links in `store-listing.md`: the graphics table is
  the file most likely to point at something that no longer exists.

## Reference

- [`markdown-link-check`](https://github.com/tcort/markdown-link-check) — status codes are shared
  between URL and file checks; `.markdown-link-check.json` holds this repo's config.
- `docs/STATIC_ANALYSIS.md` → Tier 3 (the six text gates and how they map to sweep gates 6–8).
- Builds on [[038-inspect-scope-and-the-pre-pr-sweep]] — the same rule from the other side: the
  gate is only honest when it is run over what git tracks, on the commit being pushed.
