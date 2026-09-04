# OpenLoop — Release signing & building the AAB

Google Play requires a **signed Android App Bundle (`.aab`)**, not an APK. The build is already
wired for this (`app/build.gradle.kts` reads a gitignored `keystore.properties`); this doc covers
the one-time key setup and the build/upload steps.

> **Use Play App Signing.** Let Google hold the real *app signing key*; you sign uploads with an
> *upload key*. If your upload key is ever lost, Google can reset it — losing the app signing key
> would be unrecoverable. The keystore below is your **upload key**.

---

## 1. Generate the upload keystore (one time)

Keep the keystore **outside** the repo (it's gitignored, but don't rely on that). `keytool` ships
with the JDK / Android Studio's JBR.

```bash
keytool -genkeypair -v \
  -keystore openloop-upload.jks \
  -alias openloop-upload \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype JKS
```

It will prompt for a keystore password, a key password, and a name/org (the name fields can be
anything — they aren't shown to users). **Back this file + passwords up somewhere safe** (a password
manager). Losing it means you can't sign updates with this upload key (recoverable via Google, but a
hassle).

## 2. Point the build at it

Copy the template and fill in your values:

```bash
cp keystore.properties.template keystore.properties
```

```properties
storeFile=../keys/openloop-upload.jks   # absolute path recommended, e.g. C:/Users/you/keys/openloop-upload.jks
storePassword=********
keyAlias=openloop-upload
keyPassword=********
```

`keystore.properties`, `*.jks`, and `*.keystore` are gitignored — **never commit them**. Only
`keystore.properties.template` is tracked.

## 3. Build the signed AAB

**Every release starts with the Play technical quality check** — the standing checklist in
[README → Technical quality requirements](README.md#technical-quality-requirements-enforced-feb--apr-2027):
Android vitals *Memory* rows under threshold (P90 per process state, P90/P50 below 3.5×) and the live
bundle's *App optimization* still *High*. Do it **before** the version bump, paste the numbers into the
bump PR, and stop the release if a row is red. A monthly reminder issue does the same from Feb 2027
(`.github/workflows/play-quality-reminder.yml`).

```bash
# Windows: $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
./gradlew :app:bundleRelease
```

Output: **`app/build/outputs/bundle/release/app-release.aab`**. With `keystore.properties` present
it's signed with your upload key; without it the bundle still builds but is unsigned (fine for
local checks, not for upload).

> Sanity-check the signature: `jarsigner -verify -verbose app/build/outputs/bundle/release/app-release.aab`

## 4. First upload

1. In **Play Console → your app → Release → Setup → App integrity**, opt into **Play App Signing**
   (default for new apps).
2. Create a release on a test track first (**Testing → Internal testing**), upload the `.aab`,
   and add yourself as a tester to install via the opt-in link.
3. Promote to **Production** once you've verified on-device.

## 5. Tag the release on GitHub

**Every public Play release gets a matching GitHub release.** Not to distribute binaries — for the
tag. Crashlytics reports crashes against `versionName` (e.g. `1.0.48`), and without a tag there is
no way to check out the code that shipped it.

Cut it from the merge commit of the `chore/release-<version>` bump — the exact tree
`:app:bundleRelease` was built from. Use `scripts/tag-release.ps1`, not a bare `gh release create` or
the GitHub web UI's "Draft a new release" — the script refuses to tag anything that isn't a verified
ancestor of `main` with a matching `versionName`, which a bare command (or the web UI's branch-name
target field) does not stop you from getting wrong:

```powershell
# right after the bump merges — write this sha down, it is what you build the AAB from
git fetch origin && git rev-parse origin/main

# after the .aab is uploaded to Play
.\scripts\tag-release.ps1 -Version 1.0.NN -Sha <that-sha> -Title "1.0.NN — <one-line highlight>"
```

Tag names are **bare `1.0.NN`, no `v` prefix** — `1.0.49` was the first, and every tag after it
follows the same convention.

- **Tag at upload time, not when the rollout finishes.** Internal-testing crashes report the same
  `versionName`, so the tag has to exist before the test track does. A staged rollout doesn't
  change what a tag means — it names the code, not the audience.
- **`-Sha` is not optional and the script enforces it.** Tagging `main` itself (the web UI's default)
  tags whatever the default branch points at *at click time* — main routinely moves between the bump
  merge and the release going out, and 1.0.49 was cut this way by luck (nothing had merged in the
  gap). Always pass the recorded sha.
- **Play rejected the build?** Bump `versionCode`, upload again, cut a new tag. Stale tags are cheap.
- **`-NotesFile` only needed for the odd release.** `--generate-notes` needs a previous tag to diff
  against — true for every release from 1.0.50 on. 1.0.49 was the exception: the very first tag, hand-
  written because there was nothing to diff against yet.

### Never attach the AAB or a locally built APK

The local build is signed with the **upload key**; Play re-signs with the **app signing key** and
distributes that. Android requires an update to match the installed app's signing key, so an APK
downloaded from a GitHub release cannot update a Play install — or the reverse — without a full
uninstall. That forks the install base permanently.

If a downloadable APK is ever wanted, the compatible artifact is the Play-signed **universal APK**
from *Play Console → Release → App bundle explorer → Downloads*, which carries the app signing key.
Default: attach nothing. The release is the tag plus its notes.

## Notes

- **`versionCode` must increase every upload** (`app/build.gradle.kts` → `defaultConfig.versionCode`;
  read the current value there rather than from this doc). Bump it for each new build you upload;
  `versionName` is the human-facing string.
- The app already meets Play's technical bars: `targetSdk 36` (the floor from 2026-08-31), 16 KB-aligned
  native libs, minimal permissions (`CAMERA`, the three `FOREGROUND_SERVICE*` entries for the render
  worker, legacy `WRITE_EXTERNAL_STORAGE` ≤ API 28 for the MediaStore publish; `INTERNET` is merged by Firebase).
- R8 minification + resource shrinking are on for release; if a future dependency needs `-keep` rules,
  add them to `app/proguard-rules.pro`.
