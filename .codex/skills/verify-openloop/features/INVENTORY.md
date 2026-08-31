# Feature map inventory (audit worksheet)

Fill or refresh this when auditing whether `features/` matches shipped OpenLoop UI.
Does **not** need to stay perfectly current between audits — the [README completeness gate](./README.md) and the global Cursor rule `feature-map-completeness` are the process; this file is the worksheet.

**PRDs are optional.** Prefer `strings.xml` + UI chrome. Add a PRD column only when a PRD exists.

| Surface (user-visible)               | Source hint                  | Status                        | Feature file                                                           |
| ------------------------------------ | ---------------------------- | ----------------------------- | ---------------------------------------------------------------------- |
| Onboarding `LET'S GO!`               | strings / OnboardingScreen   | mapped                        | [onboarding.md](./onboarding.md)                                       |
| Record / stop video                  | `Start recording`            | mapped                        | [record-clip.md](./record-clip.md)                                     |
| Lenses drawer + catalogue            | `Lenses and Photo Booth`     | mapped                        | [lenses.md](./lenses.md)                                               |
| Multi-face (1–2) lens                | FaceRoster / live preview    | folded into lenses            | [lenses.md](./lenses.md)                                               |
| Photo stills mode                    | `Camera` / `Take photo`      | mapped                        | [photo-capture.md](./photo-capture.md)                                 |
| Photo booth                          | booth tab / countdown        | mapped                        | [photo-booth.md](./photo-booth.md)                                     |
| Pinch zoom                           | zoom chip                    | mapped                        | [pinch-zoom.md](./pinch-zoom.md)                                       |
| Import from Photos                   | `Import a video`             | mapped                        | [import-video.md](./import-video.md)                                   |
| Gallery browse / play                | Gallery                      | mapped                        | [gallery.md](./gallery.md)                                             |
| Gallery SEND                         | `SEND`                       | folded into share             | [share-and-library.md](./share-and-library.md)                         |
| Save to Photos + share sheet         | `Saved to Photos`            | mapped                        | [share-and-library.md](./share-and-library.md)                         |
| Trim                                 | Trim screen / tab            | mapped                        | [edit-trim.md](./edit-trim.md)                                         |
| Speed constant + curve               | Speed tab                    | mapped                        | [edit-speed.md](./edit-speed.md)                                       |
| Loop direction                       | Loop tab                     | mapped                        | [edit-loop.md](./edit-loop.md)                                         |
| Filter / Looks                       | Filter tab                   | mapped                        | [edit-filter.md](./edit-filter.md)                                     |
| Delete in-progress clip              | toolbar Delete               | mapped                        | [edit-delete.md](./edit-delete.md)                                     |
| Save boomerang                       | `Save boomerang`             | mapped                        | [edit-save.md](./edit-save.md)                                         |
| Full edit → save path                | run-e2e                      | orchestrator                  | [edit-and-save.md](./edit-and-save.md)                                 |
| Permission rationale / denied        | MainActivity strings         | out of scope                  | support chrome — drive only when blocking a recipe                     |
| In-app review                        | review package               | planned loop (wave 6, logcat) | [verification-loops.md](../../../../docs/guides/verification-loops.md) |
| Play in-app update snackbar          | update package               | out of scope                  | needs a Play-signed build                                              |
| Debug report share                   | reverse-failed / save-failed | out of scope                  | support path                                                           |

When you find a new shipped control in `strings.xml` or chrome that is not listed, add a row as `missing` until a feature file exists.

## Loop status

Recipes in this folder are dump/tap instructions. A loop is `helpers/<name>_loop.py` and is the
fail-the-process proof. When the owner says "verify X", write or run the loop, do not only walk
the recipe. Full plan and onboarding implementation notes:
[`docs/guides/verification-loops.md`](../../../../docs/guides/verification-loops.md).

| Loop                            | Wave | Recipe                                              | Status                                 |
| ------------------------------- | ---- | --------------------------------------------------- | -------------------------------------- |
| `onboarding`                    | 0    | [onboarding.md](./onboarding.md)                    | shipped (`helpers/onboarding_loop.py`) |
| `capture-mode`                  | 1    | [photo-capture.md](./photo-capture.md) `photo-mode` | planned                                |
| `flip-camera`                   | 1    | [record-clip.md](./record-clip.md) `record-flip`    | planned                                |
| `gallery-empty`                 | 1    | [gallery.md](./gallery.md) `gallery-empty`          | planned                                |
| `lenses-ui`                     | 2    | [lenses.md](./lenses.md)                            | planned                                |
| `photo-booth-arm`               | 2    | [photo-booth.md](./photo-booth.md) `booth-arm`      | planned                                |
| `photo-booth-run`               | 2    | [photo-booth.md](./photo-booth.md) countdown        | planned                                |
| `record-clip`                   | 3    | [record-clip.md](./record-clip.md)                  | planned                                |
| `photo-capture`                 | 3    | [photo-capture.md](./photo-capture.md)              | planned                                |
| `pinch-zoom`                    | 3    | [pinch-zoom.md](./pinch-zoom.md)                    | planned                                |
| `edit-trim` through `edit-save` | 4    | matching `edit-*.md`                                | planned                                |
| `import-video`                  | 5    | [import-video.md](./import-video.md)                | planned                                |
| `gallery-play`                  | 5    | [gallery.md](./gallery.md)                          | planned                                |
| `share-and-library`             | 5    | [share-and-library.md](./share-and-library.md)      | planned                                |
| `in-app-review`                 | 6    | (no recipe; logcat)                                 | planned                                |
