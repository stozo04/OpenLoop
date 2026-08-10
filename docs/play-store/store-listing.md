# OpenLoop — Store listing

Copy + asset specs for **Play Console → Grow → Store presence → Main store listing**. Asset specs
are from Google's [Add preview assets](https://support.google.com/googleplay/android-developer/answer/9866151)
page (verified 2026-05-30).

> ## ⚠️ This file is the *proposal*, not the live listing — they have drifted
>
> Measured 2026-08-09 ([`../PRD-aso-discoverability.md`](../PRD-aso-discoverability.md) §2.1). The app
> name below was **never** what shipped, so don't read this file as a record of Console:
>
> | Field | Live on Play | This file | Provenance |
> |---|---|---|---|
> | App name | **`Boomerang Video Maker`** (21 chars) | `OpenLoop: Video Loop Maker` | ✅ two `play.google.com` fetches |
> | Developer name | `OpenLoop` | — | ✅ |
> | Short description | `Ad Free and Open Source Boomerang Loop Video Maker` | see below | ⚠️ mirror-sourced |
> | Full description | claims **"no tracking"** — contradicts [`data-safety.md`](data-safety.md) | discloses Firebase | ⚠️ mirror-sourced |
>
> **Two things are still owed before this file can claim to mirror Console:**
> 1. Read the ⚠️ fields verbatim in Console and correct the table above (a mirror mangled the title
>    once during the research — treat every ⚠️ as indicative, not verbatim).
> 2. Fix the live full description's "no tracking" claim. That's the half of P0 #1 that Play policy
>    actually sees; the README half is already corrected.
>
> The **title decision is deliberately open** — `LoopLens` rename vs. `OpenLoop: Loops & Lenses` —
> see the PRD §9. Copy below is written brand-neutral so either choice can drop in.

---

## Text

### App name (max 30 characters)

Pending the Issue #121 title decision. Both candidates cover the whole product (loops + lenses +
photos) and put a brand token in the field Play actually indexes:

```
LoopLens: Loops & Face Lenses
```
*(29 chars — `"loop lens"` returns no results on Play, so the brand query is uncontested.)*

```
OpenLoop: Loops & Lenses
```
*(24 chars — keeps the existing brand, but still fights four entities for the `openloop` query.)*

> **Do not** keep `Boomerang Video Maker`: zero unique tokens, a competitor ships the identical
> title, and it is Meta's `BOOMERANG` mark (USPTO Reg. 5030673, Class 009, incontestable) bare and
> unmodified in first position. **Never** write "Snapchat" in any listing field — use "face lenses",
> "AR lenses" or "face filters" (Play's [Metadata](https://support.google.com/googleplay/android-developer/answer/9898842)
> and [Impersonation](https://support.google.com/googleplay/android-developer/answer/9888374) policies).

### Short description (max 80 characters)
```
Speed-controlled video loops, live face lenses, and photos. All on your device.
```
*(78 chars. Drops the live listing's `Ad Free` opener — Play discourages promotional terms here, and
the first 80 characters are prime keyword real estate.)*

### Full description (max 4000 characters)
```
OpenLoop turns a quick clip into a smooth, speed-controlled video loop — a "boomerang" that plays forward and back. Add a live face lens, or flip the shutter to take a photo instead. All editing happens right on your phone. No account, no ads, no subscriptions, and your videos are never uploaded.

WHAT YOU CAN DO
• Capture a clip with the built-in camera, or import one you already have.
• Add a live face lens — Broccoli, Shades or Big Mouth — that follows your face as you record.
• Take photos too, lenses included, with the same shutter.
• Trim to the exact moment with a simple two-handle bar.
• Pick a direction: forward, reverse, forward-then-reverse, or reverse-then-forward.
• Set the playback speed from slow motion up to fast, with a live preview.
• Apply a color look, then save a seamless loop to your device.
• Share your loop anywhere using your phone's normal share sheet.

PRIVATE BY DESIGN
OpenLoop does 100% of its video editing on your device. Your clips and the boomerangs you make are never uploaded — they stay in the app until you delete them or uninstall. There are no accounts, no ads, and no subscriptions, and the app does not use an advertising ID. To keep OpenLoop stable and to understand which features are used, the app includes Google Analytics for Firebase and Firebase Crashlytics, which collect limited usage and crash-diagnostic data that doesn't personally identify you. There's no advertising ID and we don't sell your data. See our privacy policy for the details.

IMPORT WITHOUT GIVING UP YOUR LIBRARY
Importing uses Android's Photo Picker, so you choose one video to bring in without granting access to your whole gallery — and with no storage permission.

OPEN SOURCE
OpenLoop is free and open source under the Apache 2.0 license. You can read every line, file issues, or contribute: https://github.com/stozo04/OpenLoop

PERMISSIONS
• Camera — to record video and take photos in the app, used only on your device. Exported loops are silent (video only).

Made for people who just want to point, tap, and loop.
```
*(~1,600 chars — well under the 4,000 limit. Face detection is on-device ML Kit with a bundled
model: no extra permission, no network call, no new data-safety declaration.)*

### Other listing fields
| Field | Value |
|---|---|
| App category | **Video Players & Editors** (alt: Photography) |
| Tags | **all 5 slots must be filled** — pick the closest from Console's fixed picklist (the taxonomy isn't published anywhere, so this can only be done in Console). Aim at: boomerang / video loop / slow motion / video editor / camera |
| Contact email | gates.steven@gmail.com |
| Privacy policy URL | `https://stozo04.github.io/OpenLoop/privacy-policy.html` |
| Website | `https://stozo04.github.io/OpenLoop/` — the landing page, **not** the GitHub repo. Added Issue #121; it carries the brand token, a meta description and `SoftwareApplication` JSON-LD, and links on to both Play and GitHub |

---

## Graphic assets — exact specs

| Asset | Required? | Spec | In repo |
|---|---|---|---|
| **App icon** | Yes | **512 × 512 px**, **24-bit PNG (no alpha)**, ≤ **1024 KB**. **No baked corners** — as of **2026-03-31**, Play auto-applies a 30% corner radius at display time. Ship a square. | [`play_store_icon_512.png`](play_store_icon_512.png) |
| **Feature graphic** | **Yes (required to publish)** | **1024 × 500 px**, JPEG or **24-bit PNG (no alpha)**. Keep critical content off dead-center — Play overlays a promo-video play button there when a video is attached. | [`main-image.png`](main-image.png) |
| **Phone screenshots** | Yes — **min 2**, max 8 | JPEG or 24-bit PNG (no alpha). Each side **320–3840 px**, and a side may not exceed **2×** the other. **Recommended: 4–8 portrait shots at 1080 × 1920 px** (OpenLoop is a portrait app). | — (capture during device QA) |
| 7" / 10" tablet screenshots | Optional | Only if you market tablet support; otherwise skip. | — |

> Phone screenshots are the only image assets that need the device — capture them during your QA pass
> (see below). The icon and feature graphic are versioned in this folder; re-export them if the brand
> colors or wordmark change, and keep them in sync with `app/src/main/res/drawable-nodpi/ic_launcher_foreground.png`.

---

## Screenshot capture checklist (do during the device QA pass)

Capture these in **portrait at 1080 × 1920** (a clean emulator or a phone). **Four or more is the
real target, not the 2-shot publish minimum** — 4+ is what unlocks Play's large-format promotional
placements.

- [ ] **Camera viewfinder** — the shutter + 30s framing (the core capture screen).
- [ ] **Face lens on the viewfinder** — the lens carousel with a lens live on a face. **Needs real
      hardware**: the emulator's virtual scene has no face, so nothing renders.
- [ ] **Photo mode** — the shutter flipped to stills.
- [ ] **Trim screen** — the two-handle trim bar on a clip.
- [ ] **Editor – Loop tab** — the four direction chips with the looping preview.
- [ ] **Editor – Speed tab** — the comet speed slider.
- [ ] **Editor – Looks tab** — the filter strip (shows the color looks).
- [ ] **Gallery** — the grid of finished loops.
- [ ] *(optional)* **Onboarding** — a value-prop page ("No subscriptions & no ads").

The lens and photo shots are the ones that matter most right now: they're the features Play users
have never seen (1.0.25 → 1.0.38), and they're what the title rewrite is meant to describe.

Tip: avoid screenshots that show another app's watermark baked into imported footage — shoot the
demo clips with OpenLoop's own camera.

---

## Categorization & pricing (Console → store settings)
- **App or game:** App
- **Free or paid:** Free
- **Contains ads:** No
- **In-app purchases:** No
- **Ads / content rating / data safety:** see `content-rating.md` and `data-safety.md`.
