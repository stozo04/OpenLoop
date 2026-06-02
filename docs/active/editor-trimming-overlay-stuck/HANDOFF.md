# HANDOFF — Editor “Trimming..” overlay (pointer)

**This folder’s canonical handoff is now:**

## → [`ENGINEERING-HANDOFF.md`](./ENGINEERING-HANDOFF.md)

That document consolidates:

- Symptom and architecture
- Root causes (timeout, Samsung/HEVC, MediaCodec wedges)
- PR #50 / PR #51 / follow-up Samsung + Forward fallback
- Crashlytics and Send debug info
- Testing, QA checklist, Play Console notes
- Production repro (SM-S926B, 720p AVC, 120s timeout)

**Also see:** [`trimming-loop.md`](../../../trimming-loop.md) (short postmortem at repo root).

---

*The sections below are kept as historical context from the June 2026 library-import investigation (Pixel 10 Pro Fold logcat). Do not treat “Open questions” or “Definition of done” as current — see ENGINEERING-HANDOFF.md for shipped state.*

---

## Historical — library import investigation (pre-1.0.5)

<details>
<summary>Original HANDOFF content (collapsed)</summary>

### Symptom (June 2026)

Editor stuck on **Trimming..** after library import → Trim → editor; Save disabled. Repro on Pixel 10 Pro Fold with portrait H.264 import; log showed active pass-1 encoding without `reverse pass2:` before log ended.

### Fixes attempted before PR #51

- ViewModel session hygiene (`editorSessionActive`, `reverseGeneration`, stale loading recovery)
- VideoReverser pass-1 seek/advance fix for samples before trim start

### Hypotheses that led to PR #51

- Slow reverse vs wedged codec vs stale `previewLoading`
- Need bounded timeout → `reverseFailed` (implemented in PR #51 as `select`+`onTimeout`)
- HEVC/HDR on Samsung without normalize path

</details>
