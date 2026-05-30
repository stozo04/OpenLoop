# OpenLoop — Data Safety section answers

Transcribe these into **Play Console → Policy → App content → Data safety**. The Data safety form is
a Play Console web form (it can't live in the repo), so this file is the source-of-truth for the
answers to enter. Keep it in sync if the app's behavior ever changes.

> **Why "no data collected" is accurate here.** Google defines *collect* as "transmitting data off a
> user's device," including via any SDK/library. OpenLoop declares **no `INTERNET` permission**, bundles
> **no analytics, ads, or crash-reporting SDKs**, and does **all** processing on-device. Nothing the app
> uses transmits user data off the device, so "no data collected or shared" is truthful and defensible.
> (Sources: [Provide info for the Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469),
> [Understand Data safety](https://support.google.com/googleplay/answer/11416267).)

---

## Form answers

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | **N/A** — no data is transmitted |
| Do you provide a way for users to request that their data be deleted? | **N/A** — no data is collected. (Users can delete their on-device videos in the app's gallery, and uninstalling removes everything.) |

Because the first answer is **No**, the form will not ask you to enumerate data types. Every data
category (Location, Personal info, Financial info, Photos and videos, Audio, App activity, Device
IDs, etc.) is therefore declared as **not collected and not shared**.

### Notes for the reviewer-facing rationale (keep for your records)

- **Camera / microphone** are used only on-device to record into app-private storage; the captured
  media is never transmitted by the app.
- **Imported videos** are chosen via the Android Photo Picker (no storage permission) and copied only
  into app-private storage for editing.
- **Sharing** is user-initiated through Android's system share sheet: the user selects the destination
  app and Android performs the transfer. Per Google's guidance this **user-initiated** sharing to
  another app is not "data collection/sharing" by OpenLoop.
- **No accounts, no login, no analytics, no ads, no third-party data SDKs, no `INTERNET` permission.**

### Privacy policy

The Data safety form requires a **privacy policy URL**. Host `privacy-policy.md` (see that file's
footer) and enter the URL in the form.

### Keep this honest

If a future version adds anything that sends data off-device (analytics, crash reporting, cloud
backup, an ad SDK, remote config, the `INTERNET` permission, etc.), this declaration becomes
**inaccurate** and must be updated before that version ships. Re-review on any dependency change that
could touch the network.
