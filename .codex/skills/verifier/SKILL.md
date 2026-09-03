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

Do not lengthen the deadline or weaken assertions to hide a red result. Until issue #170 is fixed,
failure after reverse pass 1 starts is the expected product-bug receipt. The aggregate automation
command `python scripts/run-verification-loops.py --changed` discovers this verifier automatically.

