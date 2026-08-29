# Share and library save

After a loop or still is created, OpenLoop writes it to the **in-app Gallery** and publishes a copy to the device **Photos** library (`Saved to Photos`), then opens the system **Share** sheet so the user can send it to friends. Gallery preview also has a **SEND** button for anything already in the library.

## Sub-features

- `save-to-library` — after `Save boomerang` (or a still capture), a new tile appears under Gallery (`gallery_tile_*` / `Video thumbnail` / photo preview).
- `save-to-photos` — snackbar `Saved to Photos` (and optional `View`) after publish to MediaStore.
- `share-after-save` — system share chooser (`Share` / `ACTION_SEND`) appears when processing finishes; dismiss with BACK returns toward camera / snackbar.
- `share-from-gallery` — open a tile, tap `SEND` (`gallery_preview_send`), share sheet again.
- `share-photo` — same SEND path for a still (`Captured photo` / `gallery_photo_preview`).

## How to get to it (user POV)

- Finish [edit-and-save](./edit-and-save.md) (loop) or [photo-capture](./photo-capture.md) (still).
- Wait through `Creating..` / the export notification if shown.
- Share sheet → pick Messages / Drive / etc., or BACK to skip.
- Later: Gallery → tap a tile → **SEND**.

## Driving it with control.ps1

Preconditions:

- Doctor passes.
- For `save-to-library` / `save-to-photos` / `share-after-save`: run a full save from [edit-save](./edit-save.md) (or [edit-and-save](./edit-and-save.md) / [photo-capture](./photo-capture.md)) on **this** emulator.
- Evidence dir `share-and-library/` created.
- Count gallery tiles **before** save when proving a new library entry.

- **Save path.** From editor: `control.ps1 tap -Label "Save boomerang"`. Dump or logcat may show `Creating..` / `processing_screen`. Wait for share sheet or return + snackbar.
- **Share sheet.** If chooser is up, that **is** success for `share-after-save`. BACK to dismiss (Lesson 028 ClipData path — chooser should preview). Do not treat BACK as a failed save.
- **Photos snackbar.** After dismiss, dump or screenshot for `Saved to Photos`. Optional tap `View`.
- **Library.** `control.ps1 tap -Label "Gallery"`. Tile count increased, or a new `gallery_tile_*` / thumbnail exists.
- **SEND from gallery.** Tap a tile. Dump: `SEND` / `gallery_preview_send`. `control.ps1 tap -Label "SEND"`. Share sheet again.
- **Proof.** `before-gallery.txt`, `after-save-share.txt`, `after-gallery.txt`, optional `send-again.txt`. A toast without a tile is incomplete proof for `save-to-library`.

## Gotchas

- Dual write is intentional: app `filesDir` library **and** MediaStore Photos (`MediaStoreVideoPublisher` / image twin).
- Share sheet success ≠ user actually sent to a friend. Proving the chooser is enough for this skill; do not complete a real send to a personal contact.
- `share-after-save` can race the snackbar — sheet first, snackbar after close (`onShareSheetClosed`).
- Empty gallery cannot prove SEND; seed with a save from this run.
- Debug-report share (`Send debug report`) is a failure/support path, not this feature.
