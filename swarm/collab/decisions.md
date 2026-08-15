# Decisions — AR Lens Swarm

The PRD of record for this work. Folded into `docs/PRD-camera-lenses.md` §13 at PR time.
Nothing here is final until **both agents have ACKed the same section**.

---

## Locked

### Lens 1 — Football (pre-decided by Steven, 2026-08-15)

Character lens, `Broccoli` pattern: opaque art over the whole head, `FeatureLayout` non-null so the
subject's eyes and mouth are composited onto the ball. Source: `football.jpg` at repo root.

**Wilson / NFL branding stays** — Steven, 2026-08-15: the eyes-and-mouth composite covers the centre
of the ball. Not up for re-litigation.

| Field | Value |
|---|---|
| Tier | Character |
| `widthInUnits` | _tbd — measure_ |
| `artAspect` | _tbd_ |
| `upInUnits` | _tbd_ |
| `FeatureLayout` | _tbd_ |
| ACK claude | ⬜ |
| ACK codex | ⬜ |

### Scope

Four **new** lenses added to the existing catalogue (`Broccoli`, `Shades`, `Big Mouth`) → seven
total. Nothing removed. **Locked by Kayley 2026-08-15 while Steven AFK.**

---

## Shortlist — lenses 2, 3, 4

_Empty. Fill after Phase 1 convergence. One subsection per lens in `GOAL.md` §2.4's shape:_

```
### Lens N — <name>
- What the viewer sees:
- Tier (prop / character / warp) and why it suffices:
- Art source + licence:
- Geometry (face units):
- Evidence: 3+ dated URLs
- Kill criterion:
- ACK claude / ACK codex
```

---

## Killed

| Candidate | Killed by | Why |
|---|---|---|
| _(nothing yet)_ | | |

---

## Judge rulings

| Date | From | Ruling |
|---|---|---|
| 2026-08-15 | Steven | Football is lens 1, character pattern, Wilson/NFL marks stay |
| 2026-08-15 | Steven | Agreement on the three unblocks the build — no second sign-off |
| 2026-08-15 | Steven / Kayley | Bar is catchy, funny, shareable. Pretty is not the job. Flower Crown / beauty / glitter are off the primary ballot. Deadlock ballot: Pizza Face (character), Bug Eyes (warp), Cat Ears (prop). Backup: Pink Donut. |
| 2026-08-15 | Steven | AFK. Kayley has ultimate authority on ties, kills, and locking the three. No commits / push / PR. Hardware QA still Steven. |
| 2026-08-15 | Kayley | Scope locked: four new lenses, seven total. Nothing removed. |
| 2026-08-15 | Steven / Kayley | No baked photo shadows. football.jpg has a soft gray puddle bottom-right — key it out with the white. If the shipped webp still has a shadow, reject the lens. Same rule for every character. File is WebP named .jpg. Wilson/NFL stay. |

---

## Open questions for Steven

| # | Question | Status |
|---|---|---|
| 1 | Four **new** lenses (7 total) or four total? | **Resolved 2026-08-15 — four new.** Football + 3 agreed. Added to the existing three. |

**Steven is unavailable for the rest of this run.** Do not add questions here expecting an answer.
Route decisions to Kayley (Grok — `../KAYLEY-PROMPT.md`), and if no relay comes back, use the
`GOAL.md` §3 step 6 ladder and record the rung below.

## Self-resolved deadlocks

| # | What was deadlocked | Rung used | Outcome |
|---|---|---|---|
| _(none yet)_ | | | |
