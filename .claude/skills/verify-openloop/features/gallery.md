# Gallery

Gallery lists saved loops and stills in a grid. The user opens it from the camera, plays a clip, shares or deletes, and returns to the viewfinder. Empty state tells them to record or import.

## Sub-features

- `gallery-open` opens from camera (`Gallery` / `camera_gallery`).
- `gallery-empty` shows `NO LOOPS YET` and `Record your first loop to see it here!` plus import.
- `gallery-play` opens a tile (`gallery_tile_<id>`) and plays.
- `gallery-send` — preview `SEND` → share sheet; see [share-and-library](./share-and-library.md).
- `gallery-back` returns via `Back to camera` (`gallery_back`).
- `gallery-delete` uses selection + `Delete selected` (do not run this on the user's library).
- Import entry points are owned by [import-video](./import-video.md) (not proven by merely opening Gallery).

## How to get to it (user POV)

- From the camera viewfinder, tap the gallery control (usually top-left; content-desc `Gallery`).
- From empty gallery, `RECORD A LOOP` returns to camera; `Import a video` / `…or import one` starts the import picker.

## Driving it with control.ps1

Preconditions:

- Doctor passes. Camera or gallery is in the dump.
- For `gallery-play`, at least one save from [edit-and-save](./edit-and-save.md) exists on **this** emulator. Do not assume Play-store user data.
- Evidence dir `gallery/` created.

- **Open.** From camera: `control.ps1 tap -Label "Gallery"`. Dump shows `gallery_action_bar` or `NO LOOPS YET` or video thumbnails (`Video thumbnail`).
- **Empty.** On a fresh install with no clips: dump contains `NO LOOPS YET`. `RECORD A LOOP` is tappable.
- **Play.** Tap a `gallery_tile_*` if dump lists one, or the first `Video thumbnail`. Dump or logcat shows player (`ExoPlayerImpl: Init` is acceptable "confirmed via logcat").
- **Back.** `control.ps1 tap -Label "Back to camera"` (testTag `gallery_back`). Dump shows `Start recording` or `Take photo`.
- **Proof.** `camera.txt`, `gallery.txt`, `after-back.txt`. Empty and play are different sub-features — do not skip-report one as the other.

## Gotchas

- `gallery-delete` is destructive. Only delete clips this verification run created.
- Photo preview uses `Captured photo` / `Close photo` (`gallery_photo_preview`). That is stills, not a loop.
- Import picker details: [import-video](./import-video.md). If you cannot complete import without a fixture, mark it **verified-unreachable** there (`google-pro-fold-video.mp4` is often gitignored).
- Opening Gallery is not proof a new save landed; count tiles or names before and after save ([share-and-library](./share-and-library.md)).
