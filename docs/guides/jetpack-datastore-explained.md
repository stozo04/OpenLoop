# Jetpack DataStore — Explained Like You're Five

A plain-English guide to the little "memory" OpenRang uses to remember things between
app launches — what it is, where it lives, and how to peek inside it yourself.

No prior knowledge needed. If a word looks technical, it's defined in the
[Glossary](#glossary) at the bottom.

---

## What is DataStore, really?

Imagine the app keeps a tiny sticky note. On that note it writes down small facts it needs
to remember the next time you open the app — things like *"this person has already seen the
welcome screens."*

**DataStore is that sticky note.** It's Google's modern way for an Android app to remember
small settings. It only holds simple things: on/off switches, numbers, short bits of text.
It does **not** hold your videos — those are saved as real files somewhere else.

Right now OpenRang writes exactly **one** thing on its sticky note:

| The note says | Meaning | Possible values |
|---------------|---------|-----------------|
| `has_completed_onboarding` | "Has this person finished the intro screens?" | `true` or `false` |

When you tapped **LET'S GO!** at the end of onboarding, the app wrote `true` on the note.
That's why you don't see the intro again — every launch it reads the note, sees `true`, and
skips straight to the camera.

---

## Where does the sticky note live?

It's a small file tucked inside OpenRang's own private folder on the phone:

```
/data/data/com.openrang.app/files/datastore/openrang_preferences.preferences_pb
```

Two things to know:

- **It's private.** Other apps can't read it. Even *you* can't browse to it with a normal
  file manager — you need a developer tool (explained below).
- **`com.openrang.app` is the app's "package name"** — basically the app's unique ID on the
  phone. Every app has one.

---

## "Why can't I open it in the Database Inspector?"

Great question — and a common trip-up.

Android Studio has a tool called the **Database Inspector**. But it only understands one *kind*
of storage: **databases** (think: big spreadsheets with rows and columns, called SQLite).

DataStore is **not** a database. It's a different, simpler kind of storage (a sticky note, not a
spreadsheet). So the Database Inspector simply won't show it — it's not broken, it's just the
wrong tool. To look at DataStore you open the **file** directly.

---

## How to peek inside (3 ways)

Pick whichever matches your comfort level. **Way 3 is the friendliest** if you just want a
plain `true`/`false`.

### Way 1 — The point-and-click way (Android Studio)

This uses **Device Explorer**, a built-in file browser for your phone.

1. In Android Studio, open the menu: **View → Tool Windows → Device Explorer**.
2. At the top, pick your phone/emulator from the dropdown.
3. In the file tree, navigate to:
   `data` → `data` → `com.openrang.app` → `files` → `datastore`
4. Double-click `openrang_preferences.preferences_pb` to open it (or right-click → **Save As**
   to copy it to your computer).

You'll see mostly gibberish with a readable word in the middle. See
[How to read the gibberish](#how-to-read-the-gibberish) below.

> Note: this only works because OpenRang is a **debug build** (the version you build yourself).
> You can't do this to apps installed from the Play Store.

### Way 2 — The command-line way (adb)

`adb` is a little tool that lets your computer talk to your phone. It comes with Android Studio.

**Step 1 — open a terminal.** In Android Studio, click the **Terminal** tab at the bottom
(or open PowerShell). For these examples, point a shortcut at adb so you don't type the full
path every time:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
```

**Step 2 — find your device's name.** If more than one phone/emulator is connected, you have to
say which one:

```powershell
& $adb devices
```

You'll see a list like:

```
List of devices attached
58271FDCG000XC   device      <- a physical phone
emulator-5556    device      <- an emulator
```

Copy the name on the left of the device you're testing on. We'll call it `<YOUR_DEVICE>`.

**Step 3 — read the note.** This prints the file as a "hex dump" (numbers on the left, readable
text on the right) so it doesn't turn into garbage in the terminal:

```powershell
& $adb -s <YOUR_DEVICE> shell run-as com.openrang.app toybox xxd files/datastore/openrang_preferences.preferences_pb
```

(`run-as` is the magic word that lets you peek into the app's private folder. It only works on
debug builds.)

You'll get something like:

```
00000000: 0a1e 0a18 6861 735f 636f 6d70 6c65 7465  ....has_complete
00000010: 645f 6f6e 626f 6172 6469 6e67 1202 0801  d_onboarding....
```

### Way 3 — Just print true/false (easiest to read)

If you don't care about the file and only want the answer, ask the app to print it for you.

Add this one line inside `OpenRangViewModel`'s `init { ... }` block (temporarily, for testing):

```kotlin
viewModelScope.launch {
    Log.d("Prefs", "has_completed_onboarding = " +
        userPreferencesRepository.hasCompletedOnboarding.first())
}
```

Run the app, then open the **Logcat** tab in Android Studio and type `Prefs` in the search box.
You'll see a clean line like:

```
Prefs   has_completed_onboarding = true
```

Delete the line again when you're done — it's just a debugging peek.

---

## How to read the gibberish

Both ways show the **same bytes** — they just *display* them differently:

- **The hex dump (Way 2)** prints each byte as a number, e.g. `08 01`, with the readable text on the right.
- **Opening the file directly (Way 1)** shows the readable text inline, but the invisible bytes
  appear as little control-character tags like `RS`, `CAN`, `DC2`, `STX`, `BS`, `SOH`.

Don't panic at either — you only care about two things.

**1. The name.** Somewhere in the middle you'll see, in plain text:

```
has_completed_onboarding
```

**2. The value — the very last byte.** The second-to-last byte just means "a true/false value
follows"; the **last** byte is the answer:

| Hex dump (Way 2) | Direct view (Way 1) | Means |
|------------------|---------------------|-------|
| `… 08 01`        | `… BS SOH`          | **true** ✅ (onboarding done) |
| `… 08 00`        | `… BS NUL` (or nothing after `BS`) | **false** (or the name isn't there at all = not done yet) |

So a screen showing `…onboarding DC2 STX BS SOH` is the *exact same data* as the hex
`…onboarding 12 02 08 01` — the trailing `BS SOH` (= `08 01`) means **true**.

### Why the weird labels (RS, CAN, BS…)?

Text editors can't show invisible bytes, so they print a short tag instead. The ones you'll run
into here, with their byte value:

| Tag | Byte | Tag | Byte |
|-----|------|-----|------|
| `SOH` | 01 | `DC2` | 12 |
| `STX` | 02 | `CAN` | 18 |
| `BS`  | 08 | `RS`  | 1E |

They're just packaging around the two things that matter: the **name** and the **final
true/false byte**. (Tip: Way 3 — print it to Logcat — skips all of this and just says `true`.)

---

## Reset it — so you can see onboarding again

Testing the intro screens repeatedly? You need to wipe that note so the app forgets you've seen
them.

> ⚠️ **Read the warning on each option.** These delete data and **cannot be undone.**

**Option A — forget only the onboarding note (keeps your recorded videos):**

```powershell
& $adb -s <YOUR_DEVICE> shell run-as com.openrang.app rm files/datastore/openrang_preferences.preferences_pb
& $adb -s <YOUR_DEVICE> shell am force-stop com.openrang.app
```

Next time you open OpenRang, onboarding appears again. Your saved loops are untouched.

**Option B — wipe everything (a totally fresh app, like a brand-new install):**

```powershell
& $adb -s <YOUR_DEVICE> shell pm clear com.openrang.app
```

⚠️ This deletes **everything** — the onboarding note **and** all your recorded videos and
thumbnails. Use it only when you truly want a clean slate.

---

## Cheat sheet

```powershell
# Set up a shortcut to adb (run once per terminal)
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

# Which devices are connected?
& $adb devices

# Peek at the saved note (hex dump)
& $adb -s <YOUR_DEVICE> shell run-as com.openrang.app toybox xxd files/datastore/openrang_preferences.preferences_pb

# Forget onboarding only (keeps videos)
& $adb -s <YOUR_DEVICE> shell run-as com.openrang.app rm files/datastore/openrang_preferences.preferences_pb
& $adb -s <YOUR_DEVICE> shell am force-stop com.openrang.app

# Wipe everything (fresh install)  -- deletes videos too!
& $adb -s <YOUR_DEVICE> shell pm clear com.openrang.app
```

---

## Glossary

| Term | Plain meaning |
|------|---------------|
| **DataStore** | The app's "sticky note" for small settings it remembers between launches. |
| **SQLite / database** | A different, bigger kind of storage (rows and columns). DataStore is *not* this. |
| **Database Inspector** | An Android Studio tool — only for databases, so it can't show DataStore. |
| **package name** (`com.openrang.app`) | The app's unique ID on the phone. |
| **adb** | "Android Debug Bridge" — a tool that lets your computer talk to your phone. |
| **emulator** | A pretend Android phone running on your computer. |
| **physical device** | A real phone plugged in (or on the same Wi-Fi). |
| **run-as** | An adb trick that lets you peek inside a debug app's private folder. |
| **debug build** | The version of the app *you* build and run — not the Play Store version. |
| **hex dump** | A way of showing a file's raw contents as numbers + readable text. |
| **Logcat** | Android Studio's live log window where the app's messages appear. |

---

## Want the deeper version?

- The exact key and file name are defined in `app/src/main/java/com/openrang/app/data/UserPreferencesRepositoryImpl.kt`.
- Google's official explanation: [App Architecture: Data Layer – DataStore](https://developer.android.com/topic/libraries/architecture/datastore).
- Why we wrap writes safely: `docs/lessons_learned/003-datastore-write-ioexception.md`.
