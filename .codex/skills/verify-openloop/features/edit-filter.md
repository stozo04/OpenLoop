# Edit — Filter (Looks)

Applies a one-tap color look to the preview and the eventual export. Looks are Media3 effects (`VideoFilter`); they do not rebuild the reversed clip. Live preview should match the chip.

## Sub-features

- `filter-open` — toolbar `Filter` → `filter_tab_panel`, title `Choose a look`.
- `filter-original` — `Original` clears effects (`look_chip_ORIGINAL`).
- `filter-pick` — pick a non-Original look (labels: `B&W`, `Warm`, `Cool`, `Pop`, `Invert`, `Sepia`, `Party`, `Punch`, `Glow`, `Fade`, `Mint`, `Candy`).
- `filter-preview` — preview updates (visual / player); optional `Filtering..` overlay briefly.
- `filter-memory-gate` — if shown, `Looks preview paused — device is low on memory…` (`filter_tab_disabled_hint`); tapping a look retries.

## How to get to it (user POV)

- In the editor, tap **Filter**.
- Swipe the look chips; tap one that is not Original.

## Driving it with control.ps1

Preconditions:

- Doctor passes. Editor available (trim window already valid).
- Evidence dir `edit-filter/` created.

- **Open.** `control.ps1 tap -Label "Filter"`. Dump: `Choose a look` / `filter_tab_panel`.
- **Pick.** `control.ps1 tap -Label "B&W"` (or another non-Original label). Dump still on Filter tab; chip selection may be visual-only in uiautomator.
- **Original.** Tap `Original` to clear.
- **Proof.** `filter-open.txt`, `filter-picked.txt`. Screenshot of preview is stronger proof than dump alone.

## Gotchas

- Labels are product names — keep punctuation (`B&W`).
- `Preview unavailable` after a reverse failure is a product finding, not a missing Filter tab.
- Looks compose with Speed; export uses the same `toMediaEffects()` as preview.
- Low-memory gate: do not call the feature broken if the hint shows and a retry restores preview.
- Full path: [edit-and-save](./edit-and-save.md).
