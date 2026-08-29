# Feature map inventory (audit worksheet)

Fill or refresh this when auditing whether `features/` matches shipped OpenLoop UI.
Does **not** need to stay perfectly current between audits — the [README completeness gate](./README.md) and the global Cursor rule `feature-map-completeness` are the process; this file is the worksheet.

**PRDs are optional.** Prefer `strings.xml` + UI chrome. Add a PRD column only when a PRD exists.

| Surface (user-visible) | Source hint | Status | Feature file |
| ---------------------- | ----------- | ------ | ------------ |
| Onboarding `LET'S GO!` | strings / OnboardingScreen | mapped | [onboarding.md](./onboarding.md) |
| Record / stop video | `Start recording` | mapped | [record-clip.md](./record-clip.md) |
| Lenses drawer + catalogue | `Lenses and Photo Booth` | mapped | [lenses.md](./lenses.md) |
| Multi-face (1–2) lens | FaceRoster / live preview | folded into lenses | [lenses.md](./lenses.md) |
| Photo stills mode | `Camera` / `Take photo` | mapped | [photo-capture.md](./photo-capture.md) |
| Photo booth | booth tab / countdown | mapped | [photo-booth.md](./photo-booth.md) |
| Pinch zoom | zoom chip | mapped | [pinch-zoom.md](./pinch-zoom.md) |
| Import from Photos | `Import a video` | mapped | [import-video.md](./import-video.md) |
| Gallery browse / play | Gallery | mapped | [gallery.md](./gallery.md) |
| Gallery SEND | `SEND` | folded into share | [share-and-library.md](./share-and-library.md) |
| Save to Photos + share sheet | `Saved to Photos` | mapped | [share-and-library.md](./share-and-library.md) |
| Trim | Trim screen / tab | mapped | [edit-trim.md](./edit-trim.md) |
| Speed constant + curve | Speed tab | mapped | [edit-speed.md](./edit-speed.md) |
| Loop direction | Loop tab | mapped | [edit-loop.md](./edit-loop.md) |
| Filter / Looks | Filter tab | mapped | [edit-filter.md](./edit-filter.md) |
| Delete in-progress clip | toolbar Delete | mapped | [edit-delete.md](./edit-delete.md) |
| Save boomerang | `Save boomerang` | mapped | [edit-save.md](./edit-save.md) |
| Full edit → save path | run-e2e | orchestrator | [edit-and-save.md](./edit-and-save.md) |
| Permission rationale / denied | MainActivity strings | out of scope | support chrome — drive only when blocking a recipe |
| In-app review / Play update snackbar | review/update packages | out of scope | system/Play prompts, not core verify map |
| Debug report share | reverse-failed / save-failed | out of scope | support path |

When you find a new shipped control in `strings.xml` or chrome that is not listed, add a row as `missing` until a feature file exists.
