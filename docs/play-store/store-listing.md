# OpenLoop — Store listing

Copy + asset specs for **Play Console → Grow → Store presence → Main store listing**. Asset specs
are from Google's [Add preview assets](https://support.google.com/googleplay/android-developer/answer/9866151)
page (verified 2026-05-30).

---

## Text

### App name (max 30 characters)
```
OpenLoop: Video Loop Maker
```
*(26 chars. Alternatives within the limit: `OpenLoop – Speed Video Loops` (28), `OpenLoop: Boomerang Loops` (25).)*

### Short description (max 80 characters)
```
Speed-controlled video loops. 100% on-device. No ads, no signup, open source.
```
*(77 chars.)*

### Full description (max 4000 characters)
```
OpenLoop turns a quick clip into a smooth, speed-controlled video loop — a "boomerang" that plays forward and back. Everything happens right on your phone. No account, no ads, no subscriptions, and nothing is ever uploaded.

WHAT YOU CAN DO
• Capture a short clip with the built-in camera, or import one you already have.
• Trim to the exact moment with a simple two-handle bar.
• Pick a direction: forward, reverse, forward-then-reverse, or reverse-then-forward.
• Set the playback speed from slow motion up to fast, with a live preview.
• Apply a color look, then save a seamless loop to your device.
• Share your loop anywhere using your phone's normal share sheet.

PRIVATE BY DESIGN
OpenLoop does 100% of its video processing on your device. It has no servers and doesn't even use the internet permission, so your videos physically cannot be sent anywhere by the app. There are no accounts, no analytics, and no advertising. The clips you create stay in the app until you delete them or uninstall.

IMPORT WITHOUT GIVING UP YOUR LIBRARY
Importing uses Android's Photo Picker, so you choose one video to bring in without granting access to your whole gallery — and with no storage permission.

OPEN SOURCE
OpenLoop is free and open source under the Apache 2.0 license. You can read every line, file issues, or contribute: https://github.com/stozo04/OpenRang

PERMISSIONS
• Camera — to record video in the app.
• Microphone — to capture audio while you record (exported loops have audio removed).
Both are used only on your device.

Made for people who just want to point, tap, and loop.
```
*(~1,400 chars — well under the 4,000 limit.)*

### Other listing fields
| Field | Value |
|---|---|
| App category | **Video Players & Editors** (alt: Photography) |
| Tags | boomerang, video loop, slow motion, video editor (pick from Console's tag list) |
| Contact email | gates.steven@gmail.com |
| Privacy policy URL | *(host `privacy-policy.md` — see its footer — and paste the URL)* |
| Website (optional) | https://github.com/stozo04/OpenRang |

---

## Graphic assets — exact specs

| Asset | Required? | Spec |
|---|---|---|
| **App icon** | Yes | **512 × 512 px**, **32-bit PNG with alpha**, ≤ **1024 KB**. (Generate from `ic_launcher`; the in-repo neon-infinity mark is the source of truth.) |
| **Feature graphic** | **Yes (required to publish)** | **1024 × 500 px**, JPEG or **24-bit PNG (no alpha)**. |
| **Phone screenshots** | Yes — **min 2**, max 8 | JPEG or 24-bit PNG (no alpha). Each side **320–3840 px**, and a side may not exceed **2×** the other. **Recommended: 4–8 portrait shots at 1080 × 1920 px** (OpenLoop is a portrait app). |
| 7" / 10" tablet screenshots | Optional | Only if you market tablet support; otherwise skip. |

> Phone screenshots are the only image assets that need the device — capture them during your QA pass
> (see below). The icon already exists; the feature graphic is a 1024×500 banner you design once.

---

## Screenshot capture checklist (do during the device QA pass)

Capture these in **portrait at 1080 × 1920** (a clean emulator or a phone). Aim for 5–6 so the
carousel tells the whole story:

- [ ] **Onboarding** — a value-prop page ("No subscriptions & no ads").
- [ ] **Camera viewfinder** — the shutter + 30s framing (the core capture screen).
- [ ] **Trim screen** — the two-handle trim bar on a clip.
- [ ] **Editor – Direction tab** — the four direction chips with the looping preview.
- [ ] **Editor – Speed tab** — the comet speed slider.
- [ ] **Editor – Looks tab** — the filter strip (shows the color looks).
- [ ] **Gallery** — the grid of finished loops.

Tip: avoid screenshots that show another app's watermark baked into imported footage — shoot the
demo clips with OpenLoop's own camera.

---

## Categorization & pricing (Console → store settings)
- **App or game:** App
- **Free or paid:** Free
- **Contains ads:** No
- **In-app purchases:** No
- **Ads / content rating / data safety:** see `content-rating.md` and `data-safety.md`.
