# Edit — Delete clip

Throws away the in-progress capture/import session from the editor and returns to the camera. This is **not** Gallery multi-select delete. Toolbar **Delete** opens the same discard dialog family as Trim back (`Discard this clip?`), with **Keep editing** as the dismiss action.

## Sub-features

- `delete-open` — toolbar `Delete` → dialog `Discard this clip?` / body about deleting the captured clip.
- `delete-confirm` — tap `Discard` (`discard_confirm`) → camera viewfinder (`Start recording` or `Take photo`).
- `delete-cancel` — tap `Keep editing` → stay on editor; clip preserved.

## How to get to it (user POV)

- While editing a clip, tap **Delete** on the bottom toolbar.
- Confirm Discard, or Keep editing to abort.

## Driving it with control.ps1

Preconditions:

- Doctor passes. Editor up with a throwaway clip from **this** run (record or import). Do not delete the user's only keepers.
- Evidence dir `edit-delete/` created.

- **Open.** `control.ps1 tap -Label "Delete"`. Dump: `Discard this clip?`, `Discard`, `Keep editing`.
- **Cancel path (safe).** `control.ps1 tap -Label "Keep editing"`. Dump still has editor / `Save boomerang`.
- **Confirm path.** Re-open Delete → `control.ps1 tap -Label "Discard"`. Dump: camera (`Start recording` / `Take photo` / `Gallery`). Optional brief `Deleting..` overlay.
- **Proof.** `delete-dialog.txt`, `after-cancel.txt` and/or `after-discard.txt`.

## Gotchas

- Distinct from [gallery](./gallery.md) `Delete selected` / undo — different surface, different strings.
- Discard is destructive for the session clip only; it should not remove already-saved Gallery loops.
- Trim's back affordance can show the same dialog with dismiss verb `Keep` instead of `Keep editing` — still the discard family.
- Prefer cancel-first when exploring; only confirm when the recipe requires leaving the editor.
