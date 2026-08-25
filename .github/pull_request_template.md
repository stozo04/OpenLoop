## Description

Please include a summary of the change, the motivation behind it, and what problem it solves. Include relevant context and list any dependencies that are required for this change.

## Related Issue

Fixes # (issue number, if applicable)

## Type of Change

- [ ] ✨ **Feature**: A new feature (non-breaking change which adds functionality)
- [ ] 🐛 **Bug Fix**: A bug fix (non-breaking change which fixes an issue)
- [ ] 🎨 **Design/Style**: Visual changes, layouts, animations, or asset integrations
- [ ] ⚙️ **Refactor**: Code changes that neither fix a bug nor add a feature
- [ ] 📝 **Documentation**: Changes or additions to documentation/guides
- [ ] 🔧 **Chore**: Build configuration changes, dependency updates, or toolchain adjustments

## How Has This Been Tested?

Please describe the tests that you ran to verify your changes. Provide instructions so we can reproduce.

- **Manual Verification**: (e.g., tested on Pixel 7 API 34, verified permission flow)
- **Automated Tests**: (e.g., ran Gradle compile tasks)

## Checklist

- [ ] 🧪 My changes have been verified locally and work as expected.
- [ ] 🔍 I have performed a self-review of my own code.
- [ ] ✍️ I have commented my code, particularly in hard-to-understand areas.
- [ ] 📖 My changes generate no compile warnings or errors (`allWarningsAsErrors` is on).
- [ ] 🧹 **Pre-PR sweep GREEN on the final commit** (`.\scripts\pre-pr-sweep.ps1` → `build/sweep-receipt.json`): build 0 e:/0 w:, zipalign, Lint 0/0, tests 0 failures, Markdown/tables/links/cspell/JSON at zero. Inspect Code export: parsed to 0 / SKIPPED because: ______
- [ ] 🏪 **Play-facing docs aligned**: if this PR changes permissions, data collection, file storage, or user-facing features/lenses — privacy policy (MD + HTML), data safety, and store listing updated to match (`docs/DEFINITION_OF_DONE.md`).
- [ ] 🧹 The git branch is clean and references to obsolete branches have been pruned.
