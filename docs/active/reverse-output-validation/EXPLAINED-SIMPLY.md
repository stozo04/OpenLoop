# What Broke, What We Did, and Why the Fix Works — The Simple Version

> The grown-up versions live in [`IMPLEMENTATION.md`](./IMPLEMENTATION.md) (the plan) and
> [`RESEARCH.md`](./RESEARCH.md) (the evidence). This is the story in plain words.

---

## How OpenLoop makes a backwards video

To make a loop, the app needs your clip playing **forwards and then backwards**.
Phones don't have a "play it backwards" button for video files, so the app builds
the backwards part itself using two machines that live inside every phone:

- **The Unpacker** (real name: *decoder*) — opens the video and turns it back into
  individual pictures, one at a time.
- **The Packer** (real name: *encoder*) — takes pictures and squeezes them into a
  new video file.

The pictures travel from the Unpacker to the Packer on a **conveyor belt**
(real name: *Surface*). The app feeds the pictures in backwards order, the Packer
packs them, and out comes a backwards video. Done thousands of times a day on
millions of phones. Normally boring.

## What went wrong on the Galaxy S23

On this one phone, in this one backwards-building mode, something weird happens:

**The Unpacker puts pictures on the conveyor belt… and they never arrive at the
Packer.** Nobody knows exactly why — it's a quirk deep inside Samsung's Android 13
software for that phone. The Packer just sits there, receives nothing, and when the
app says "that's everything!", the Packer shrugs and seals up a **completely empty
box** — a video file with a label on the outside and zero pictures inside.
(598 bytes. A real video that size would be a few hundred *thousand* bytes.)

That quirk was the spark. But three bugs in **our** app turned a spark into a fire:

1. **Nobody checked the box.** The app saw "a box exists" and declared victory.
   The empty box got handed to the next machine down the line (the *movie
   combiner*, real name: Media3 Transformer), which opened it 3 seconds later and
   crashed with a confusing error — `"The asset loader has no audio or video track
   to output"` — that pointed at the wrong machine entirely. That's the error you
   spent days staring at.

2. **The empty box got filed in the "already done" cabinet** (the *cache*). The
   app's rule was "if a box exists and weighs more than zero, reuse it." So every
   time you pressed Save again, the app grabbed the same empty box and failed
   instantly. The phone could never recover, even by luck.

3. **The crash slipped past the safety net.** The saving worker had a list of
   error types it knew how to catch. This error wasn't on the list (a Java
   technicality — `ExportException` isn't an `IOException` or a
   `RuntimeException`), so it fell through, skipped the cleanup, and surfaced as a
   generic failure.

One bonus discovery: the **preview** in the editor had been failing the exact same
way all along — silently. The "reverse" you watched before saving was an empty
clip that played for 0 seconds. It just *looked* fine because the forward part
played normally.

## How we found it

- Your logcat was the security-camera footage. It showed the Packer's own diary:
  *"Received 0 buffers and encoded 0 frames"* — the empty box being sealed —
  3 seconds before the crash everyone was looking at.
- We tested the **same app** on a Galaxy S25: worked perfectly. Same machines,
  same conveyor belt. So it wasn't our recipe — it was this phone's software.
- We pulled the actual empty boxes off your S23 and turned them into **test
  fixtures** — now our tests check against the real broken thing, forever.
- Best of all: we found the bug reproduces with a tiny 12-picture test video, in
  an automated test, in seconds. That gave us a laboratory.

## What we changed

**1. Count the pictures.** The app now counts every picture it puts in the box.
Zero pictures = stop and raise an alarm. No more pretending.

**2. Check boxes before trusting the cabinet.** Before reusing a cached backwards
video, the app opens it and checks there's at least one picture inside. Empty box?
Throw it away and build a fresh one. (Fun fact: our first version checked box
*weight* instead of contents — and a test on a Pixel immediately proved that wrong
by making a perfectly good video that weighed almost nothing. Checking contents is
the only honest check.)

**3. Fix the safety net.** The saving worker now catches that missing error type,
cleans up properly, and fails gracefully.

**4. Be honest with the user.** If something still fails, you get a clear message
and a **"Send debug report"** button instead of a mystery.

**5. THE FIX: when the box comes out empty, switch Packers.** Phones have two
kinds of Packer:

- a **hardware Packer** — a dedicated chip; super fast; this is the one whose
  conveyor belt is broken on the S23
- a **software Packer** — the phone's main processor doing it "by hand"; slower,
  but it always receives its pictures

Now, if a backwards build produces an empty box, the app immediately rebuilds it
with the software Packer — and **remembers** ("this phone's fast Packer is broken
today") so it doesn't waste time re-trying the broken one on every save.

## Why this works

We tested all three combinations live on your actual S23:

| Unpacker | Packer | Result |
|---|---|---|
| software | hardware (fast chip) | Empty box — pictures vanish on the belt 📦💨 |
| hardware | hardware (fast chip) | Even worse — the machines crash outright 💥 |
| software | **software** | **Works. Every picture arrives. Real backwards video.** ✅ |

The problem was never the pictures, the recipe, or the Unpacker — it was that this
phone's fast Packer never receives pictures in this specific mode. The software
Packer doesn't use that broken delivery path, so it just… works. Slightly slower,
only on phones that need it, and invisible to every phone that doesn't.

And the proof: the very first successful Save this S23 has ever done —
*"Worker result SUCCESS"* — with the log showing the whole play-by-play: empty box
detected → alarm raised → Packers switched → real video built → loop saved. 🎉

## The two lessons worth keeping

1. **A machine that can produce an empty box must count what it puts in the box.**
   "The file exists" is not the same as "the work happened."
2. **When a phone misbehaves, test your guesses on the phone.** Two of our three
   theories about the fix were wrong, and twenty minutes of on-device testing was
   what found the right one.

---

### Tiny glossary (kid word → real word)

| In this doc | Real name |
|---|---|
| Unpacker | `MediaCodec` decoder (`c2.android.avc.decoder` etc.) |
| Packer | `MediaCodec` encoder (`c2.qti.avc.encoder` = hardware, `c2.android.avc.encoder` = software) |
| Conveyor belt | `Surface` (the decoder renders onto the encoder's input surface) |
| Empty box | the 598-byte moov-only MP4 with zero video samples |
| "Already done" cabinet | the reversed-clip cache in `cache/scratch/reversed/` |
| Movie combiner | Media3 `Transformer` (builds the final forward+backward loop) |
| Count the pictures | `ReverseOutputValidator` + the zero-sample throw in `VideoReverser` |
| Switch Packers + remember | the software-encoder fallback + `zeroFrameEncoderWedgeSticky` |
