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

## Notes

- **`versionCode` must increase every upload** (`app/build.gradle.kts` → `defaultConfig.versionCode`,
  currently `1`). Bump it for each new build you upload; `versionName` (`1.0.0`) is the
  human-facing string.
- The app already meets Play's technical bars: `targetSdk 36` (≥ the API-35 floor), 16 KB-aligned
  native libs, minimal permissions (`CAMERA` only).
- R8 minification + resource shrinking are on for release; if a future dependency needs keep rules,
  add them to `app/proguard-rules.pro`.
