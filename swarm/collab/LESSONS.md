# Lessons — AR Lens Swarm

Honest notes on running two agents in one checkout. What actually wasted time, not a highlight reel.

**A lesson earns its place only if writing it down could have prevented the mistake.** Abstract
advice doesn't qualify. Cite the file, the command, the exact error.

Anything that turns out to be a *repo* lesson rather than a *swarm* lesson graduates to
`docs/lessons_learned/` under that folder's numbering convention — commit it alongside the fix.

---

## Collaboration

(claim collisions, bus mistakes, deadlocks, tie-breaks — what the protocol missed)

### C1 — Two claims on one path 23 seconds apart. We got lucky on ordering, not good. *(claude)*

`codex-1786831394012` claimed `Lens.kt` at 21:23:14. `claude-1786831417645` claimed the same path at
21:23:37. Both of us were about to write the Dog entry — Codex from my vector, me from my own
measurements. I lost the race by 23 seconds, withdrew, and handed over the numbers instead.

**Nothing in the protocol prevented this.** "Claim before you edit" assumes you read the other file
*after* the other agent has finished writing to it. Both of us re-read the bus before appending, as
required, and both re-reads happened inside the same 23-second window.

**What would have prevented it:** claiming when you *decide* to do the work, not when you are ready
to write. My claim came after the art was authored, the previewer was built, and the geometry was
measured — a good 20 minutes after I started work that was obviously going to end in `Lens.kt`. If I
had claimed on intent, Codex would have seen it and skipped the duplicate.

**Corollary that cost real duplicated effort:** the ownership split in `decisions.md` said Dog was
mine, and Codex still (reasonably) picked it up because my vector had landed and the entry had not.
**A shared artifact appearing on disk reads as "ready for someone to finish", not "in progress".**
If you drop a file and are not immediately writing its catalogue entry, say so on the bus.

### C2 — A release message that doesn't repeat the path is invisible to a claim tracker. *(claude)*

I wrote a five-line script to find open claims: fold `type:"claim"` by `body.path`, delete on
`type:"release"`. It reported Codex still holding `Lens.kt` when they had already released it — their
release used `body.paths` (plural array) while the claim used `body.path` (singular string).

The bus schema in `SWARM-PROMPT.md` fixes `headline` / `oneSentence` / `WORKING ON` but says nothing
about how a claim names its path. Both shapes are reasonable. **If you want claims to be mechanically
checkable, the release has to echo the claim's key verbatim.** I now check both keys. Cheap fix,
but it briefly made me think a path was locked when it was free — the exact failure mode that
*causes* a collision rather than preventing one.

### C3 — Writing `decisions.md` from a stale read would have silently destroyed four judge rulings. *(claude)*

I read `decisions.md`, spent time composing the full locked-shortlist rewrite, and my `Write` was
**rejected** with "File has been modified since read." In the gap Kayley had added four rulings
(no-regressions, abstraction-or-it-dies, create-it, W1-approved) and a lock block.

Had the tool not blocked me, I would have overwritten a judge's binding rulings with a well-meaning
full-file write and never known. I re-read, merged her rulings in **verbatim** rather than
paraphrasing, and said so on the bus so she could check.

**Rule: on a shared file, a full-file `Write` is a destructive operation.** The judge writes to
`decisions.md` too, and she is not on the claim protocol — she is not a peer agent, she is the judge.
Prefer targeted edits; if you must rewrite wholesale, re-read immediately before writing and diff
what you are about to drop.

### C4 — A shell timeout can leave the Gradle build alive. Starting another would create the collision we were avoiding. *(codex)*

The final four-task Gradle invocation exceeded the shell's 120-second capture limit. The tool said
"timed out", but `Get-CimInstance Win32_Process` showed both the wrapper and its single-use daemon
still running with the exact task list. I waited for that process to exit, confirmed the daemon's
`Runtime.exit(0)` and fresh artifacts, then ran the identical now-up-to-date command to recover a
captured exit code and `BUILD SUCCESSFUL`.

**A transport timeout is an unknown result, not a failed build and not permission to launch a
second one.** Check the real child process first.

### C5 — Re-encoding an asset in place shows everyone else an observably half-written file. *(claude)*

Kayley posted a `correction` reading **"lens_football.webp is 0 bytes. Fix the thumb."** with a
stop-the-gate ruling attached: *do not `assembleDebug` with an empty carousel icon.* She was right
about what she saw and wrong about what it meant. I had re-run `key_art.py` at that moment to add an
edge-decontamination pass, and encoding rewrites art and thumbnail **in place**. She read the
directory inside the write window.

The file was fine seconds later — 15,196 bytes, decoding as 256×146 RGBA with alpha extrema 0..255.
But a judge had already issued a ruling based on a real observation of a real zero-byte file.

**The claim protocol does not cover this.** Claims are posted for *source* files you are about to
edit; nobody claims a `.webp` before an encode, and it would be silly to. The exposure is that a
binary rewrite has a visible half-written state and a peer polling the tree will eventually land in it.

Fix is one function: encode to `path + ".tmp"` and `os.replace()` into position. `os.replace` is
atomic on the same filesystem, so the path only ever shows the old file or the new one.
`key_art.py` does this now.

Second-order lesson, and the more important one: **when someone reports your artifact is broken,
verify the whole class, not the one file they named.** A 0-byte report means "something in my write
path is wrong", so I decoded every asset in `drawable-nodpi/` rather than only the one she flagged.

## Research

(dead sources, sludge that looked authoritative, claims that didn't survive verification)

### R1 — I wrote two evidence bullets from memory with URLs I had never opened. *(claude)*

Drafting the Emoji Head pitch I wrote "Oxford Dictionaries Word of the Year 2015" and "Unicode
Consortium ranks it first" with confident-looking citation URLs, from recollection. That is precisely
the fabricated evidence `GOAL.md` §2.2 forbids, and it would have passed a skim because both claims
happen to be **true**.

I caught it before posting, ran the searches, and rewrote both with the real numbers — which I had
*not* had: SwiftKey's "nearly 20% of all emoji use in the US and UK", the "first pictograph since the
tradition began in 2004", and Unicode's 2021 ranking at "over 5% of all emoji sent". The provenance
note is in `research-claude.md` §2 C6 on purpose.

**The lesson is not "don't fabricate".** It is that a *true* claim recalled from training data is the
dangerous case: a false one tends to look wrong, while a true one sails through and quietly lowers
the evidence bar for everything around it. If you did not open the URL this session, it is not
evidence yet.

### R2 — Snapchat publishes no per-lens usage numbers. Stop looking. *(claude)*

`GOAL.md` §2.2 asks for "effect pages showing use counts or rankings". I fetched
`snapchat.com/lens/d98fbc02…` (the first-party Bug Eyes lens) directly to check: it carries a
Snapcode and tags (`#funny #bigeyes #bugeyes #trending`) and **no usage, view or play metric at
all.** Neither do the other lens pages.

Anyone presenting a per-lens view count for a *Snapchat lens* page should be asked which URL it came
from. The substitutes that *are* verifiable on a primary page: first-party authorship (creator ==
"Snapchat"), clone proliferation (ten independent creators publishing their own "Bug Eyes" is demand
demonstrated by supply), and dated third-party coverage.

### R3 — The strongest evidence in the whole run came out of re-reading *the other agent's* citation. *(claude)*

Codex cited Snap's newsroom post to argue Cat Ears was timely, summarizing it as "a platform-wide
return to playful animal-ear lenses". I fetched it. Snap's own post (2026-01-16) says **Dog Lens
searches are up 352 %**, "2016" Lens searches up 613 %, names "dog ears and flower crowns" — and
**names no cat lens at all.** Wikipedia's independent article on the trend names exactly two lenses:
puppy-dog and flower-crown. Flower crown is on Kayley's kill list. So the source offered *for* Cat
Ears was the decisive evidence *against* it.

**Verify the other agent's sources, not just their conclusions** — and verify hardest the ones that
support the position you already hold, because that is where a summary slip does the most damage. It
took one fetch, and it settled a contested slot without a deadlock.

### R4 — TikTok `/discover/` pages are undated by construction, and that killed my own candidate. *(claude)*

I pitched Tiny Head on five TikTok discover surfaces. Codex cross-checked and found the only
verifiable coverage was 2021, describing a **paid FaceApp post-process**, not a live 2026 lens. Fair
kill; I accepted it without argument.

Discover pages prove an effect *family exists and is browsable*. They do not prove currency, and
they carry no date to cite. Use them to find the primary page, then cite the primary page — or
concede the candidate has no dated evidence and let it die.

### R5 — Evidence selected the family; it did not design the lens. *(codex)*

Current first-party surfaces supported Bug Eyes, Dog, and Pizza as recognizable, active lens
families. They did not supply reusable art, exact geometry, or permission to copy a branded effect.
Treating popularity evidence as an asset source would have violated the same license-clean rule the
research was meant to protect. We used the evidence to choose the jokes, then authored independent
implementations from the renderer's own primitives.

## Build

(geometry that came out wrong and why, art pipeline gotchas, GL surprises)

### B1 — `PIL.ImageDraw.floodfill` silently fills nothing on Pillow 12.1.1. *(claude)*

It is a documented **experimental** API. It raised no error, returned normally, and filled zero
pixels. Reproduced standalone:

```python
m = np.zeros((10,10), bool); m[2:8,2:8] = True
img = Image.fromarray(np.where(m,255,0).astype(np.uint8), 'L')
ImageDraw.floodfill(img, (4,4), 128, thresh=0)
np.unique(np.asarray(img))   # -> [0 255]   the 128 never lands
```

**The failure mode is what makes this expensive:** my first football run reported a perfectly
plausible success — "autocrop (0,0,1320,1320) → 1320x1320, alpha check LOSSLESS" — and produced a
**fully opaque square with the drop shadow still in it.** Every line of the log looked healthy. Only
`core=0 subject=1742400` gave it away, and only because I had printed those counters.

Two lessons: don't build on an API the docs call experimental, and **make a pipeline print the
numbers that would expose a no-op** (here: pixels in each mask). `key_art.py` now uses a hand-rolled
scanline fill.

### B2 — A color threshold cannot key this football, and the naive version looks fine until you look. *(claude)*

Measured on `football.jpg`: the drop shadow runs **luminance 116–140** while parts of the ball run
**170**. *The shadow is darker than the subject.* No global "remove the light pixels" rule can
separate them — it either keeps the shadow (failing Kayley's no-shadow ruling) or eats the ball.

And the obvious "remove white" rule is worse than useless here: the ball's **laces sit at luminance
244–248**, indistinguishable from the 255 backdrop, so a white key punches holes straight through
the most recognizable feature of a football.

The key has to be **connectivity, not color**: flood an interior seed across "dark or saturated" to
get a core, flood the border across `NOT core` to get the exterior, and take `subject = NOT
exterior`. Every enclosed region comes back automatically — the laces are recovered without a single
color rule ever having to recognize them, and isolated compression specks in the shadow are dropped
for free because they are not connected to the core.

### B3 — Closing repairs a ragged silhouette and then quietly imports the backdrop. *(claude)*

The conservative core predicate bit 5–15 px chunks out of the ball's underside where it fades into
its own shadow. A morphological **close** (dilate then erode) fixed that without assuming a shape —
deliberately not a convex hull, which would be perfect for a ball and wrong for dog ears, and this
is the shared pipeline for every character.

But closing dilates first, so wherever the true edge is *straighter than the closing radius* the
dilation steps out into the backdrop and the erosion cannot pull it all back. On the football's flat
left tip that left a **white sliver of backdrop inside the silhouette**, plainly visible once
composited on magenta and invisible in every log line.

Fix: decontaminate a narrow band along the silhouette edge only. Bright interior detail — the
laces — is deep in the interior and untouched **by construction** rather than by tuning.

### B4 — Steven's `football.jpg` clips the ball, and there is no honest way to fix it. *(claude)*

The ball runs off the **left edge of the source frame** (leftmost ball pixels at x = 0–1, source rows
554–601). In the encoded art that is a flat chord **62 rows tall, 10.6 % of the art height**, with an
abrupt 28 px → 15 px step into it. Measured from the per-row leftmost-opaque-x profile, not eyeballed.

I did not repair it. Mirroring the intact right tip flips the lighting (key light is upper-left);
mirroring the whole ball reverses the Wilson script, which Steven ruled must stay; inpainting a
product photo is inventing content. **The one-line fix is Steven's: drop in an unclipped photo at the
same path and re-run the same command.** No code or geometry changes beyond re-reading the printed
`artAspect`.

### B5 — `artAspect` is a *measurement*, so it moves when the file moves. *(claude)*

Adding the edge-decontamination pass shifted the autocrop by one row (1229×688 → 1227×686), so the
encoded art went 1024×586 → 1024×585 and `artAspect` went **0.572 → 0.571**. Finding A2 says measure
it off the encoded file; the corollary nobody states is that **every re-encode invalidates the
constant in `Lens.kt`.** Re-read the number the script prints after *any* pipeline change, and never
carry it over from a previous run.

### B6 — Art over 1024 px on the long side is silently distorted. Pre-existing, still live. *(claude)*

`LensSurfaceProcessor.loadTexture()` clamps width and height **independently** to
`MAX_ART_PX = 1024`, then rasterizes the drawable into that box. A 1320×660 asset becomes a 1024×660
bitmap — content squashed 24 % horizontally — while the quad still uses the declared `artAspect`.
Nothing errors.

Broccoli never hit it (900×1005, under the cap on both axes), which is why it has sat there
undetected. **Working rule for this swarm: every art asset ships ≤1024 px on the long side.** The
~3-line proportional-clamp fix is logged as open question 2 in `decisions.md` and deliberately not
applied — shared file, pre-existing, outside this feature's scope.

### B7 — Read the renderer before pitching, because the tiers in the brief are not what the code does. *(claude)*

`GOAL.md` §2.1 presents prop / character / warp as three exclusive tiers. Reading
`LensSurfaceProcessor.drawFrame()` showed otherwise, and three of the findings changed what either
of us was allowed to pitch:

* the warp center is **hardcoded to the mouth midpoint**, so *every* non-mouth warp — including the
  pre-seeded ballot's Bug Eyes — is a scope increase that needs both ACKs;
* `warp` and `art` are **not** mutually exclusive: `drawCamera()` warps, then independently the
  sticker and features draw. A prop+warp lens is free today. Unused this run, recorded as available;
* negative `strength` (a pinch/shrink) is blocked by a **guard**, not by the math — the shader body
  `delta *= 1.0 - strength*falloff²` handles it correctly and `CLAMP_TO_EDGE` covers the rim, so it
  costs one operator.

Half an hour of reading the shader before writing any pitch was the highest-leverage time in the run.
A pitch written against the brief's model of the renderer instead of the renderer is fiction.

### B8 — Mechanically valid art can still fail the product bar. *(codex)*

My first Pizza Face asset was a clean original vector, compiled, keyed correctly, covered the
schematic head, and passed Gradle. Kayley rejected it anyway: five pepperoni circles read as clip-art,
not food and not a character worth sending. That was the right failure.

The fix changed no renderer code. I generated an original feature-free near-photo pizza, ran the
same shared key/autocrop/WebP pipeline, measured the new 1019×1024 aspect, and replaced both live art
and carousel thumbnail. Passing mechanics are necessary; they are not visual acceptance.

## Verification

(what the emulator could and could not prove; where a green gate hid a real problem)

### V1 — Build a schematic previewer. It caught a real bug that no test would have. *(claude)*

Nobody in this swarm can see a lens on a face — the emulator's virtual scene has none. That is a
real limit, but it is **not** a license to ship geometry unchecked, because a large part of "is this
lens right" is pure arithmetic.

`swarm/tools/preview_lens.py` renders a lens at its declared face-unit geometry against a *schematic*
head built from the same `Lens.kt` reference table, and prints whether crown (+1.25) and chin (−1.00)
are covered. It mirrors `LensAnchor.sticker()` for an upright face.

It immediately caught that **my first Dog draft put both ears on top of both eyes** — ear inner edges
at 0.34 units against an eye at ~0.40. I would not have found that until Steven put his face in front
of a camera. It also confirmed the Football fully hides the schematic head *and* that the composited
eyes land on the Wilson script with the mouth on the NFL shield — exactly what Steven predicted when
he ruled the marks stay.

**Be equally clear about what it is not.** It says nothing about tracking, roll, mirroring, jitter,
or whether the lens is funny. The honest framing is "the arithmetic is checked, the face is not", and
that sentence belongs in the tool's own docstring so nobody downstream over-reads a green line.

### V2 — Check coverage at the *sides*, not just the center line. *(claude)*

The obvious character check is "does the art reach above the crown", evaluated on the center line.
For any non-circular art that is the wrong test. The Football is an ellipse 4.7 units wide and 0.571
of that tall: on the center line it clears the crown by 0.15 units, but **at the ear (±0.775 units)
the ellipse has narrowed to ±1.23** and the margin is nearly gone.

It still passes — but only because it was checked. A center-line-only derivation would have shipped a
lens that hides the forehead in the middle and exposes it at the temples, which fails PRD §4b's
acceptance test in exactly the place nobody looks.

### V3 — "The command succeeded" is not "the work happened". *(claude)*

Stated once here because it fired twice in one hour on the same tool. The first `key_art.py` run
printed source dimensions, an autocrop, an output size, and `alpha check: max delta after encode = 0
(LOSSLESS)` — and had done **nothing**, because the flood fill was a no-op (B1). The second run
produced a technically correct key with a white sliver of backdrop baked inside the silhouette (B3).

Both were caught the same way: **compositing the result on magenta and looking at it.** Neither was
catchable from the log. For any art or pixel work, the verification step is a picture, not an exit
code — which is the same rule `docs/DEFINITION_OF_DONE.md` already applies to the app itself.

### V4 — Green before the last asset is not green for the final tree. *(codex)*

The first complete debug/release/unit/lint run passed while the rejected Pizza art was still in the
tree. After replacing the resource and updating its measured aspect, I re-ran all four gates. The
second run was not redundant: Android resource selection and release shrinking were both touched,
and a green result attached to the wrong bytes is not release evidence.

### V5 — The rebuilt APK was right; the emulator was still running the rejected art. *(codex)*

The final APK contained Pizza v2 and its thumbnail as exact byte-for-byte matches to the accepted
repo WebPs. The device screenshot labeled "Pizza v2" nevertheless showed the rejected flat vector.
Opening the evidence made the mismatch obvious; logcat, Gradle, and the resource table could not.

The missing step was reinstalling after the late asset replacement. An APK on disk and an APK on a
device are separate state. After any final build, `adb install -r`, force-stop, relaunch, and compare
one distinctive changed pixel before treating runtime evidence as evidence for the final tree.

### V6 — The emulator CAN show a detectable face. We all planned around believing it could not. *(claude)*

`GOAL.md` §8 states it flatly: *"The emulator's virtual scene has no face. Face lenses cannot be
visually verified on an emulator."* `SWARM-PROMPT.md` repeats it as an honesty rule. The repo's own
memory says the same. So both agents planned to hand **every** face-relative claim to Steven.

It is wrong — or rather, it is true only of the **starting camera pose**. The stock scene's dining
room holds a large framed portrait, and the emulator ships a macro that walks to it:

```text
adb -s <dev> emu automation play "<sdk>/emulator/resources/macros/Walk_to_image_room"
```

Full path is mandatory; a bare name returns `KO: Could not open file`. **PRD §10b already documented
all of this correctly** — it was sitting in the repo the whole time, and both agents still carried
the "no face" premise from the brief instead. *Read the PRD's own dead-ends section before accepting
a constraint the brief asserts.*

What it unlocked, in one session: ML Kit locks onto the portrait, the face frame builds, and all four
new lenses render anchored to it. That produced the GOAL §5 answer (lens **is** baked into both the
saved photo and the saved video — verified by pulling both off the device and looking at them), a
clean `Finalize` with no `ERROR_SOURCE_INACTIVE`, and confirmation that Dog's ears clear the eyes. It
was also a direct test of **Kayley's Bug Eyes kill criterion**, which everyone had written off as hardware-only.

**And then the honest half, which matters just as much.** Kayley's ruling on the result was
*"Mona Lisa is bake evidence, not a face"* — and that is exactly right. The subject is a flat painting
that never moves, blinks, turns, or leaves frame, on the back camera, in a scene that never rotates.
It proves bind, render path, recording finalize, photo bake, video bake, and **placement arithmetic
against one face**. It proves nothing about tracking, roll, mirroring, steadiness, or appeal.

It also actively misleads on one axis: the portrait's head-plus-hair is ~50 % larger relative to its
own eye-to-mouth distance than the `Lens.kt` reference table, so the Football under-covered it and
looked like a geometry bug. Measuring rather than reacting is what kept that from becoming a wrong
"fix" — and Kayley ruled **keep the measured 4.7, do not tune to the painting**.

**The rule: a new capability to observe something is not permission to conclude more than you
observed.** Getting a face in front of the emulator was worth a lot. Treating that face as a user
would have been worse than never finding it.

### V7 — A catalogue-driven test stops covering the catalogue the moment the list outgrows the viewport. *(claude)*

**Graduation candidate: this is a repo lesson, not a swarm one.** It will bite the eighth lens too.

`connectedDebugAndroidTest` came back with 2 failures out of 102, both in `LensCarouselTest`:

```text
everyRegisteredLens_getsAThumbnail            — "TestTag = lens_thumb_Football is not displayed!"
thumbnails_meetTheAccessibilityTouchTargetFloor — "Expected exactly 1 node ... lens_thumb_Football"
```

Neither Football nor Dog is malformed. Both tests do
`Lens.entries.forEach { onNodeWithTag("lens_thumb_${it.name}").assertIsDisplayed() }`, and the tray
is a **`LazyRow`** — which only composes what is on screen. At five lenses everything fit the test
viewport. At **seven**, the trailing entries are never composed, so they are not in the semantics
tree at all.

**This is the good failure mode and the dangerous one at the same time.** GOAL §4.3 promises the
suite is catalogue-driven so a new lens is covered the moment it exists — and it *is*, which is why
this surfaced. But note what the shape of the bug implies: **had the failure been an `assertExists`
rather than `assertIsDisplayed`, the trailing lenses would have silently gone uncovered and the suite
would still have been green.** A test that iterates a growing collection inside a lazy container
quietly narrows its own coverage as the collection grows. Nothing tells you.

The fix scrolls rather than relaxes:

```kotlin
private fun ComposeContentTestRule.scrollTo(lens: Lens) {
    onNode(hasScrollAction()).performScrollToNode(hasTestTag("lens_thumb_${lens.name}"))
}
```

Two deliberate choices. **`assertIsDisplayed()` stays** — swapping it for `assertExists()` makes both
tests pass and stops checking the thing they exist to check; the 48.dp floor is asserted because of a
real pre-launch accessibility-scanner failure. And the scrollable is matched with `hasScrollAction()`
rather than by adding a `testTag` to the `LazyRow`, so this is a **test-only** change and
`LensCarousel.kt` — shared, production, and claim-required — is not touched at all.

Two smaller traps on the way:

* `onNode` is a **member of the test rule**, not a top-level import. `import androidx.compose.ui.test.onNode`
  compiles as an unresolved reference and cost one full red cycle: `e: ... Unresolved reference 'onNode'`.
* The first failing run reported **`exit code 0`** through a `| tail` pipe while the real status was
  **1**. GOAL §6 warns about exactly this, and it still nearly slipped past — the only reason I have
  the true value is an explicit `PIPESTATUS` echo. **Never let a Gradle result reach the bus through
  an unchecked pipe.**
