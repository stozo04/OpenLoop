# Active Features

This directory tracks features currently in development. Each feature gets its own folder containing at least one implementation document.

## Structure

```
docs/active/
├── README.md                          ← You are here
├── boomerang-editor/
│   └── IMPLEMENTATION.md              ← Parent design doc (the "what are we building")
├── boomerang-rollout/
│   ├── README.md                      ← Delivery plan: the 7 thin slices
│   └── NN-<slice>.md                  ← One PRD per slice
└── <feature-name>/
    ├── IMPLEMENTATION.md              ← Required: at minimum, the implementation plan
    ├── RESEARCH.md                    ← Optional: background research, alternatives considered
    └── DECISIONS.md                   ← Optional: feature-specific decisions not in the PRD
```

## Rules

**Every active feature must be a folder.** No loose `.md` files in `docs/active/`. The folder name is the feature being built, matching the git branch name where possible (e.g., branch `feature/datastore-preferences` maps to folder `datastore-preferences/`).

**Every feature folder must contain at least one `.md` file.** At minimum, an implementation plan covering what's being built, why, how, and what "done" looks like. Use `IMPLEMENTATION.md` as the default name.

**Implementation docs should cover:**

- Problem statement — what's broken or missing
- Scope — what's in and what's explicitly out
- Architecture — how it fits into the existing system (reference `docs/PRD-mission-control.md`)
- Implementation steps — ordered, with file-level specifics
- Testing plan — what new tests are needed
- Acceptance criteria — how you know it's done

**When a feature ships:** Move the folder from `docs/active/` to `docs/completed/`. The implementation doc becomes the historical record of what was built and why.

## Current Active Features

The **Boomerang editor rollout** is the active work — the core "loop generation" feature, delivered as
7 thin vertical slices. The parent design lives in [`boomerang-editor/`](./boomerang-editor/IMPLEMENTATION.md);
the slice-by-slice delivery plan and per-slice PRDs live in [`boomerang-rollout/`](./boomerang-rollout/README.md).

| Feature | Branch | Status |
|---------|--------|--------|
| Boomerang slice 03 — Tabbed editor + Direction tab | `feature/boomerang-slice-03-direction-tab` | Built; PR #25 open (awaiting merge) |
| Boomerang slice 04 — Speed tab | `feature/boomerang-slice-04-speed-tab` | Next up — see `boomerang-rollout/04-editor-speed-tab.md` + the slice-04 KICKOFF |
| Boomerang slices 05–07 — Reps / Share sheet / Gallery edit | (per slice) | Planned — specs in `boomerang-rollout/` |
| Onboarding page-1 video | `feature/loop-1` | Built; PR #34 open — see [`onboarding-video/`](./onboarding-video/IMPLEMENTATION.md) |
| Editor **Trimming..** overlay stuck (library import) | `feature/UI-Updates` | **Fixed** — see [`editor-trimming-overlay-stuck/HANDOFF.md`](./editor-trimming-overlay-stuck/HANDOFF.md) |
| Editor memory / OOM hardening (Crashlytics `ef2823cf…`) | `feature/editor-memory-oom` (suggested) | Spec — [`editor-memory-oom/IMPLEMENTATION.md`](./editor-memory-oom/IMPLEMENTATION.md) |
| Crashlytics reverse-preview codec (`3a506c4e`, `b09e527`) | `fix/crashlytics-3a506c4e-pass1-codec-lifecycle` | PR [#62](https://github.com/stozo04/OpenLoop/pull/62) — [`crashlytics-reverse-preview/HANDOFF.md`](./crashlytics-reverse-preview/HANDOFF.md) |

> Now in `docs/completed/`: Boomerang slice 01 (variable-length capture) & slice 02 (auto-route trim +
> default save), VideoStorageRepository / remove Context from ViewModel (Issue #10, PR #17), Target SDK
> Upgrade + Android 16 Doc-Prep (Issue #7, PRs #13 & #15), Jetpack DataStore Preferences (PR #5), and
> Permission Rationale Flow (PR #12).
>
> The earlier monolithic `loop-generation/` and `full-feature/` plans were **superseded** by the
> boomerang-rollout slices and removed.
