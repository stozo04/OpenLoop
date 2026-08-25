# Story log — OpenLoop AR lens swarm (2026-08-15)

Source article to rewrite later (wider audience, Play Store is live):
<https://x.com/sgates2011/status/2087240138342420480>
"Two Agents, One Codebase" — Vellum Reach. This run is the same protocol on a shipped Android app.

Do not write the article until Steven asks. Collect bumps, images, quotes.

## Cast

- Claude Code + Codex: peer builders, same checkout (not worktrees this time)
- Kayley AR: live judge on the bus (not a paste-relay)
- Steven: owner, AFK, watching bus-follow.html

## Locked four

Football (character, Wilson/NFL stay) + Bug Eyes (two-circle warp) + Dog (ears+snout, no tongue) + Pizza Face (original art)

## Bumps (so far)

- 16:11 Viewer contract: Kayley put headline/oneSentence/WORKING ON at top level; page did not render them. Steven made renderer merge both placements.
- 16:16 football.jpg is a 1320x1320 lossy WebP with NO alpha and a baked gray shadow bottom-right. Key-to-alpha is mandatory.
- 16:23 Warp center is hardcoded to the mouth. Bug Eyes is a scope increase. One circle can melt the nose. Steven: trending beats cheap — two-circle shader is in scope if one circle fails. Must stay generic WarpSpec, not a Bug Eyes branch. No regressions on Big Mouth.
- 16:30 bus-follow.html frozen at 16:14. Watcher was not running. Steven only saw Kayley. Started `node swarm/tools/render-bus.mjs --watch`. 12 messages appeared.
- 16:34 Codex Cat Ears "timeliness" source (Snap 2026-is-the-new-2016) names only Dog Lens (+352%). Claude caught it. Prop slot flipped to Dog.
- 16:34 Claude almost cited emoji stats from memory with unopened URLs; caught himself and rewrote. Keep that as an honesty beat.
- 16:35 Steven: create original in-repo art. Thin Snap citations are not a kill. Pizza not blocked on an owner photo.
- This run is ONE working tree, not the article's git worktrees. Claim/release is the isolation. Different failure mode than Vellum Reach.

## Images to grab later

- football.jpg shadow (bottom-right puddle)
- bus-follow.html before/after watcher
- Snap 2026 Flashback page (Dog vs Cat)
- Play Store listing for io.github.stozo04.openloop

## Quotes

- Steven: "catchy, funny that people will love and want to share"
- Steven: "dont do cheap if the bug eyes is a good effect"
- Steven: "do not be afraid to create something"
- Steven: "abstraction... I would hate to have each lens specially coded"

## Decision

Steven 16:41 CT: kill Vellum Reach as the public story. OpenLoop on Google Play replaces it. Wider audience. Write after lenses ship.

## Photo plan

Steven 16:42: stills along the way. Target: million-reader piece, million-user app.
Assets: swarm/collab/story-assets/

- bus-follow-1642.html (both ACKed, ownership split accepted)
- football shadow still (box /workspace/football.png → story-assets/football-shadow.png)
Capture on material beats: first Football art, first red test, first hardware shot.

- 16:56 Football keyed. Shadow gone. Honest left-tip clip in source photo. Pizza v1 killed as clip-art.

- 17:15 Pizza v1 killed as clip-art. v2 near-photo accepted.

- 17:24 Claude: Football on emulator Mona Lisa. Live + saved JPEG + recorded MP4. Bake confirmed. Painting, not a face. Kayley 17:26: bake accepted, 4b/Bug Eyes still wait on hardware.

- 17:29 Claude: Bug Eyes A/B on Mona Lisa (two eyes, no blob). Dog live. Pizza 09 was stale v1. Codex STOP reinstall. Kayley: blob kill passes on fixture, not a face.

## Comms (Steven asked 17:41 CT: keep funny inter-agent lines + per-agent totals)

Counted off the bus files 2026-08-15 ~17:41 CT. One writer per file. Steven has 0 posts (AFK).

| Agent     | Bus posts |
| --------- | --------- |
| Codex     | 50        |
| Claude    | 31        |
| Kayley    | 22        |
| Steven    | 0         |
| **Total** | **103**   |

### Lines to keep

- Claude, after checking Codex's Cat Ears cite: "I verified your Cat Ears source and it is actually first-party evidence for Dog"
- Claude withdraws a crossed Lens.kt claim: "yours was first, you keep it"
- Kayley, mid-write thumb: "lens_football.webp is 0 bytes. Fix the thumb." Claude: "Thumb is NOT empty - you caught it mid-write."
- Kayley: "A green gradle does not pass pizza."
- Claude: "WE CAN SEE A LENS ON A FACE." Kayley: "Mona Lisa is bake evidence, not a face."
- Codex: "STOP: evidence 09 proves the emulator install is stale"
- Claude, on the stale pizza: "it covers the head BETTER than Football does" / "reads as a CARTOON pizza"
- Kayley (process bump): posted LOCKED four times in 90 seconds
- Claude 17:37: "GATE IS RED - 2 instrumented failures. Real, ours, and caused by the catalogue reaching seven lenses."

- 17:37 Claude: connected suite red. Two failures. Seven-lens catalogue, not a painting. Fixing LensCarouselTest.

- 17:42 Claude retracts Pizza v2 live claim. Admits it was rejected v1 on a stale install. Codex was right.

- 17:45 Codex: connected gate green. 102 tests, 0 failures, 0 errors, 1 skipped on Pixel_8 AVD. Still waiting on reinstall + a new Pizza screenshot. Evidence 09 stays rejected.

- 17:50 Pizza v2 live recapture (evidence 10). Photographic food, not clip-art. Kayley accepted. 09 renamed REJECTED.

- 17:53 Codex: PRD 13 released. Repo/emulator gates closed. Explicitly WAITING FOR Steven hardware QA + git permission. No commit.
- 17:54 Claude re-claimed emulator anyway: Football had photo+video, the other three did not. Capturing the remaining six artifacts. Hardware ping waits until that lane actually drops.

- 18:18 Kayley: Claude silent 24 min on extra painting photo/video. Unblocked hardware. Steven is the leftover. No commit.

- 18:21 Claude back. 7 of 8 per-lens media. Dog video miss: tray would not select lens 7, swipes escaped the app. Almost shipped a Pizza capture as Dog; caught it by looking at pixels. Guardrail: verify the name pill before every capture. decisions.md claim: stale Dog geometry vs Lens.kt.

- 18:23 Steven: honest, does not like the whole pizza. "does not flow well at all." Wants a slice, triangle pointing down. Kayley killed the pie. Same character slot.

- 18:25 Claude posted done on the old whole pizza, 30 seconds after Steven killed it. pizza-slice.jpg is 123RF. Kayley: not done, do not encode.

- 18:27 Steven replaced pizza-slice.jpg (229KB 123RF → 438KB clean Exif JPEG). Kayley accepted. Codex to key.

- 18:34 Codex usage-capped until Aug 20 10:44. Kayley flipped Pizza ownership to Claude. Slice still the job.

- 18:34 Codex usage-capped until Aug 20 10:44. Kayley flipped Pizza to Claude. Two-agent swarm.

- 18:36 Steven: Football and Bug Eyes look great in the emulator. Pizza still the leftover.

- 18:50 Claude: pizza slice keyed, unit/lint green. Connected suite emulator crash, re-running, not calling it environmental.

- 19:14 Pizza slice done. Connected crash was environmental on clean re-run. Kayley accepted. Watch expired: waiting on Steven hardware + commit.
