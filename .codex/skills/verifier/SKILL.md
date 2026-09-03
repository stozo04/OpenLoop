---
name: verifier
description: Run the installed-APK regression verifier for OpenLoop issue #170, where reverse preview wedges after a nonzero trim start. Use when the user says /verifier or asks to verify the reverse-preview trim fix.
---

# Reverse Preview Trim Verifier

From the repository root, run:

```powershell
python .codex/skills/verify-openloop/helpers/reverse_preview_trim_loop.py
```

It records a fresh clip, sets a nonzero trim start, enters `Forward then reverse`, and requires
`viewModel.ensureReversed.ok` within 30 seconds. It saves UI, screenshot, logcat, codec-resource,
and source receipts under `%TEMP%\openloop-verify\...\reverse-preview-trim`.

Do not lengthen the deadline or weaken assertions to hide a red result. Issue #170 was fixed in
`6a5f266`, so a `reverse.pass1.start` with no `pass1.loop.done` is now a regression, not the
expected receipt. The run is also rejected as vacuous unless the `pass1.preroll` receipt shows the
trim start landed between sync samples. That receipt is a `Log.d`, so this verifier needs the debug
build — which is what `python scripts/run-verification-loops.py --changed` installs when it
discovers this verifier automatically.
