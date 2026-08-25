# Lesson 037 — A per-identity hold must handle the detector re-labelling the same thing

**Origin:** Multi-face lenses (`docs/PRD-multi-face-lenses.md`), 2026-08-25 — caught in review
**Applies to:** `camera/lens/FaceRoster.kt`, `camera/lens/FaceTracker.kt`, and any future "track
N things by id and hold through drop-outs" code (hands, pets, a second tracker)

## What went wrong

The single-face tracker rode out a blink with one rule: *hold the last face for 350 ms, but a fresh
detection always wins.* Going to two faces, the hold became per tracking id — correct on its own
(one person turning away must not blink the other's lens off) — and the "fresh always wins" rule
quietly disappeared, because "fresh" now had to mean "fresh **for that id**".

ML Kit does not keep an id across a dropped frame reliably. Lose a face for a frame or two and it
often comes back under a **new** id. With a per-id hold that means:

* the old id is still held, in a slot, for up to 350 ms;
* the new id is a fresh, untracked face;
* → one person wears **two** lenses (stickers *and* features) for a third of a second, in the
  recording too — and with both slots full, the new id is locked out until the hold expires.

This regressed the *solo* selfie, the one case the PRD promised was untouched. Nothing in the
first test suite could see it: `FaceRoster.assign` was pure and tested, but the hold lived in the
ML Kit-bound `FaceTracker`, so the fresh/held interaction was never on the JVM.

**The first fix reopened it one layer down** (PR #145 review). Adoption handed the slot to the
*new* id and published the snapshot under it. The roster was now right — one lens, no lock-out —
but `LensMotion` keys its springs and eased mouth by `trackingId`, so on every relabel it started
that person's animation from rest: the tongue snapped straight, the mouth-driven layer collapsed
to its rest size and re-eased. Same solo-selfie regression, now invisible to `FaceRosterTest`
because the ids it asserted were the new ones — the tests pinned the bug.

## Pattern

1. **When a single-thing rule becomes per-identity, list what the old rule did implicitly.**
   "Fresh beats held" was doing two jobs — ride out a blink, *and* absorb a re-label. Per-id hold
   keeps the first and drops the second unless you put it back on purpose.
2. **Dedupe a held entry against a fresh one by geometry, not by id — the *nearest* one.** A
   fresh face with no slot standing within one face-unit (eye-to-mouth, square space — Lesson 032)
   of a held-but-unseen slot holder *is* that person. Nearest, not first-reported: two people cheek
   to cheek can both be relabelled in one frame, and first-match lets their identities cross.
3. **Then re-key to the original id, never the new one.** Publish the adopted snapshot under the
   holder's id and alias new → original on ingest, for as long as the holder is held. The identity
   a tracker publishes is a *contract* with everything downstream that keeps a `Map<Int, State>`
   — a label change that leaks through resets all of it. Same slot index too, so draw order and
   the other face are untouched.
4. **Do not "fix" it by letting fresh outrank held wholesale.** That reopens the door the hold
   closed: a third person entering during someone's blink would take their slot.
5. **Keep the hold in the pure class, and test across the boundary.** The moment the hold and the
   slot rule live together (`FaceRoster.update`) every one of these scenarios is a five-line JVM
   test. A hold inside the detector callback is untested by construction. And one test must feed
   the roster's real output into the consumer (`LensMotionTest` → `FaceRoster` → `LensMotion`):
   a roster test that asserts ids in isolation cannot tell "right slot" from "right identity".

## Detection checklist

* `grep -n "fresh\|held\|hold" app/src/main/java` — any per-id hold must have a same-entity
  adoption step next to it, and a test named for id churn.
* A tracker test suite with no case for "same thing, new id" has not tested the hold.
* `rg -n "HashMap<Int" app/src/main/java/.../camera/lens` — every per-id map downstream of the
  roster must be fed ids the roster has already canonicalised; if a new consumer appears, the
  boundary test above gains an assertion.
* Symptom on device: a lens briefly doubles on one face after a blink or a fast head turn, a
  second person is lensed only after a visible delay — or a hanging part snaps straight / a
  mouth-driven part collapses for a beat on a blink (the re-key variant).

## Reference

* Regression guards: `FaceRosterTest` — `aFaceRedetectedUnderANewId_keepsItsSlotAndItsOriginalId_soloCase`,
  `…_withTheOtherFacePresent`, `theNewId_staysFoldedOntoTheOriginal_onEveryLaterFrame`,
  `anAlias_diesWithTheFaceItPointedAt`, `adoption_picksTheNearestCandidate_notTheFirstReported`,
  `aNewFace_farFromTheHeldOne_isNotMistakenForIt`, `adoption_needsTheFaceToHaveMovedLessThanOneFaceUnit`;
  `LensMotionTest.aFaceRelabelledByTheDetector_keepsItsSpringsAndMouth_throughTheRoster`.
* [ML Kit face detection — tracking ids](https://developers.google.com/ml-kit/vision/face-detection/android)
  (`enableTracking()`; ids are consistent across frames only while the face is continuously detected).
* Lesson [032](./032-normalized-overlay-math-needs-square-space.md) for the square-space distance.
