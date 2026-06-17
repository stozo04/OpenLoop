# OpenLoop — Privacy Policy

**Effective date:** 2026-06-17
**App:** OpenLoop (package `io.github.stozo04.openloop`)
**Developer:** Steven Gates · gates.steven@gmail.com
**Source code:** https://github.com/stozo04/OpenLoop (open source, Apache License 2.0)

---

## Summary

OpenLoop is a camera app for creating speed-controlled video loops ("boomerangs"). **All video
editing happens on your device, and your videos are never uploaded to us or anyone else.** To keep
the app stable and to understand which features people use, OpenLoop does collect a limited amount of
**usage and crash-diagnostic data** through Google Analytics for Firebase and Firebase Crashlytics.
This data does not personally identify you. There are no accounts, no ads, and no advertising ID, and
we do not sell your data.

## Information we collect

**Your videos stay on your device.** Everything you record, import, edit, and export is processed
entirely on your device and is never sent to us or to any third party by OpenLoop.

OpenLoop includes two Google/Firebase tools that send a limited amount of diagnostic and usage data
to Google, which processes it on our behalf:

- **Google Analytics for Firebase** — records app-usage events (for example, which screens you open
  and actions such as saving or sharing a loop), an **approximate location** (country/region/city
  level) that Google derives from your IP address, and a random **app-installation identifier** (the
  Firebase App Instance ID).
- **Firebase Crashlytics** — if the app crashes, records the crash details: a stack trace, the app's
  state at the time, point-in-time device information, and a random installation identifier used to
  count how many users a given crash affected.

This data **does not personally identify you.** It is linked only to a random, resettable
per-installation identifier (a "pseudonymous" ID) — not to your name, email, or any account, because
OpenLoop has no accounts or sign-in. OpenLoop **does not use an advertising ID**, shows no ads, and
does not sell or share this data with third parties for their own purposes. All of it is sent securely
over HTTPS.

Because these tools communicate with Google's servers, the app requests the `INTERNET` permission. It
is used only for the usage and crash reporting described here — never to upload your videos.

## What we do not collect

- Your name, email address, phone number, or any account — there is no sign-up or login.
- Your photos or videos — these never leave your device through OpenLoop.
- An advertising ID — ad-ID collection is disabled and the app shows no ads.
- Precise (GPS) location, contacts, messages, or audio.

## Permissions

- **Camera (`CAMERA`)** — to record video in the app's viewfinder. Video is written only to the app's
  private storage on your device. Exported boomerang loops are silent (video only).
- **Internet (`INTERNET`)** — used only to send the usage and crash diagnostics described above. It is
  not used to transmit your videos.
- **Notifications (`POST_NOTIFICATIONS`)** — to show progress while a loop is being saved in the
  background.

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

OpenLoop is a general-audience app and is not directed at children. We do not knowingly collect
personal information from children. The usage and crash diagnostics described above are pseudonymous
and are not used to identify or build profiles of individual users.

## Data retention and deletion

Your on-device videos remain until you delete them in the app or uninstall OpenLoop. The usage and
crash diagnostics are retained by Firebase for a limited period under Google's standard retention
settings and then deleted automatically.

The diagnostics are pseudonymous and are not linked to your name, email, or any account, so we have
no way to identify or single out one individual's records — and therefore cannot action a request to
delete a specific person's diagnostic data. You stay in control in these ways:

- **Uninstalling OpenLoop stops all further data collection.**
- **Clearing the app's storage (or uninstalling) resets the random installation identifiers**, so
  future data can't be tied to past data.
- **Firebase automatically deletes the diagnostic data** after its retention period.

## Changes to this policy

If this policy changes, the updated version will be published at this same URL with a new effective
date.

## Contact

Questions about this policy: **gates.steven@gmail.com**

---

> **Live URL (paste this into the Play Console):**
> **https://stozo04.github.io/OpenLoop/privacy-policy.html**
>
> This Markdown file is the readable source; the page actually served to users is the self-contained
> `docs/privacy-policy.html`, published via **GitHub Pages** (main branch, `/docs` folder). Keep the two
> in sync if you edit the policy. Paste the URL above into **Play Console → Policy → App content →
> Privacy policy** and into the **store listing** Privacy Policy field.
