# OpenLoop — Privacy Policy

**Effective date:** 2026-05-30
**App:** OpenLoop (package `io.github.stozo04.openloop`)
**Developer:** Steven Gates · gates.steven@gmail.com
**Source code:** https://github.com/stozo04/OpenRang (open source, Apache License 2.0)

---

## Summary

OpenLoop is a camera app for creating speed-controlled video loops ("boomerangs"). **All
processing happens entirely on your device. OpenLoop does not collect, transmit, sell, or share any
personal data, and it has no servers, accounts, analytics, or advertising.** The app does not even
request the `INTERNET` permission, so it is technically incapable of sending your data anywhere.

## Information we collect

**None.** OpenLoop does not collect or transmit any personal or usage data off your device. There is
no sign-up, no account, no login, and no analytics or crash-reporting service in the app.

## Camera and microphone

OpenLoop uses the following device permissions, solely on your device, only while you use the
relevant feature:

- **Camera (`CAMERA`)** — to record video in the app's viewfinder. Video is written only to the
  app's private storage on your device.
- **Microphone (`RECORD_AUDIO`)** — to capture audio while you record a clip. (Exported boomerang
  loops have their audio removed.)

Captured and imported video never leaves your device through OpenLoop. It is not uploaded, backed up
to any OpenLoop server (there are none), or shared with us or any third party.

## Videos you import

When you import an existing video, OpenLoop uses the Android **Photo Picker**, which lets you choose
a single video without granting the app broad access to your photo/video library and **without any
storage permission**. OpenLoop copies only the one clip you pick into its private workspace so it can
be edited; it cannot see any of your other media.

## Files OpenLoop stores

OpenLoop saves the clips and boomerangs you create in the app's **private, app-specific storage** on
your device (not visible to other apps). You can delete any of them at any time from the in-app
gallery. Uninstalling the app removes all of them.

## Sharing

When you tap **Share**, Android's system share sheet opens and **you** choose where to send the
boomerang (for example a messaging or social app). That transfer is performed by Android and the app
you select — it is initiated and controlled entirely by you. OpenLoop does not transmit your video on
its own and is not involved beyond handing the file to the app you pick.

## Children's privacy

OpenLoop does not knowingly collect any data from anyone, including children. Because the app
collects no data at all, it does not process personal information of children.

## Data retention and deletion

OpenLoop stores nothing off-device, so there is nothing for us to retain or delete. On-device videos
remain until you delete them in the app or uninstall OpenLoop.

## Changes to this policy

If this policy changes, the updated version will be published at this same URL with a new effective
date.

## Contact

Questions about this policy: **gates.steven@gmail.com**

---

> **How to publish this (so you can paste a URL into the Play Console):**
> The Play Console requires a publicly reachable **URL**, not a file. Easiest options:
> 1. **GitHub Pages** — enable Pages for the `OpenLoop` repo and host this page, e.g.
>    `https://stozo04.github.io/OpenRang/privacy-policy`.
> 2. **Direct GitHub link** — the rendered file URL on the default branch also works as a stop-gap:
>    `https://github.com/stozo04/OpenRang/blob/main/docs/play-store/privacy-policy.md`.
> Paste whichever URL you host into **Play Console → Policy → App content → Privacy policy**, and into
> the **store listing** Privacy Policy field.
