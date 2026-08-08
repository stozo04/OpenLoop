# Lesson 028 — `ACTION_SEND` FileProvider shares must set `ClipData` (not only `EXTRA_STREAM`) so the system chooser can preview

> Origin: Galaxy S20+ RTL (SM-G985F) after a successful loop save — sharesheet opened, but logcat
> showed `SecurityException: Permission Denial: reading …fileprovider/videos/boom_….mp4 from …
> uid=1000 requires … grantUriPermission()`.

## What went wrong

`buildBoomerangShareIntent` set `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION` and wrapped the
intent in `Intent.createChooser`. That is enough for the **eventual** target app once the user
picks one. It is **not** enough for the **system ChooserActivity** (uid 1000 on Samsung) to peek
the URI for a thumbnail/preview while the sheet is open.

`createChooser` copies **ClipData** (and its URI grants) onto the chooser Intent. It does **not**
copy `EXTRA_STREAM`. Without `intent.clipData = ClipData.newRawUri(…, uri)`, the chooser’s peek
hits FileProvider with no grant → `SecurityException` (share still works; preview/metadata may be
blank or noisy in logcat).

## Pattern

```kotlin
Intent(Intent.ACTION_SEND).apply {
    type = "video/mp4"
    clipData = ClipData.newRawUri(subject, uri) // required for chooser preview grants
    putExtra(Intent.EXTRA_STREAM, uri)          // keep for older receivers
    putExtra(Intent.EXTRA_SUBJECT, subject)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
```

- Prefer ClipData + intent flags over `Context.grantUriPermission("android", …)` (harder to revoke;
  Google’s secure-file-sharing guidance prefers temporary intent flags).
- Do **not** export the FileProvider.

## Detection checklist

- Grep share builders for `EXTRA_STREAM` / `FileProvider.getUriForFile` — every hit must also set
  `clipData` (or use `ShareCompat.IntentBuilder.addStream`, which does).
- Logcat on Galaxy after Save: `Permission Denial: reading …fileprovider… uid=1000` → missing
  ClipData.
- Instrumented assert: `intent.clipData?.getItemAt(0)?.uri == streamUri`.

## Reference

- [FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider) —
  ClipData + grant flags example for share intents.
- [Sharing a file](https://developer.android.com/training/secure-file-sharing/share-file)
- `MainActivity.buildBoomerangShareIntent`, `ShareBoomerangTest`.
