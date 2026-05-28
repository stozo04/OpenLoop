# Active Features

This directory tracks features currently in development. Each feature gets its own folder containing at least one implementation document.

## Structure

```
docs/active/
├── README.md                          ← You are here
├── datastore-preferences/
│   └── IMPLEMENTATION.md              ← Implementation plan for this feature
├── loop-generation/
│   └── IMPLEMENTATION.md
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

| Feature | Branch | Status |
|---------|--------|--------|
| Jetpack DataStore Preferences | `feature/datastore-preferences` | PR #5 open |
