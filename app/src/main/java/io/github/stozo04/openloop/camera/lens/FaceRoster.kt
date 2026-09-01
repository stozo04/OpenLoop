package io.github.stozo04.openloop.camera.lens

import kotlin.math.hypot

/**
 * Decides **which** faces get a lens each frame, and rides each of them through the detector's
 * dropped frames — pure, no ML Kit types, JVM-tested (`FaceRosterTest`). [FaceTracker] is the
 * thin ML Kit glue in front of it.
 *
 * ## Slots
 *
 * The rule, from `docs/PRD-multi-face-lenses.md` D2: **locked slots, largest fills the gap.** A
 * face that already holds a slot keeps it for as long as the detector keeps reporting its tracking
 * id (plus the hold below); a slot that frees up goes to the largest face not yet tracked. Never
 * re-rank by size while the slots are held — two similar-sized faces would trade the lens back and
 * forth every few frames. With `maxFaces = 1` this is exactly the single-face lock lenses shipped
 * with. [assign] is that rule on its own, stateless.
 *
 * ## The hold
 *
 * A detector at `PERFORMANCE_MODE_FAST` misses the odd frame — on a blink, a fast turn, or a
 * motion-blurred frame. Dropping the face for those makes the lens blink off and back on, which
 * reads as broken. Holding the last good snapshot for [holdMs] rides through the gap without adding
 * any latency while the face IS being found, because a fresh detection of the same id always wins.
 *
 * The hold is **per face** (D3): one person turning to profile must not blink the other person's
 * lens off, and their slot stays theirs until the hold expires — only then does it free up. A face
 * merely being held cannot *take* a slot it did not already have; it might be gone for good.
 *
 * ## ID churn
 *
 * ML Kit often re-detects a briefly lost face under a new tracking id. The roster adopts the
 * nearest fresh face within [SAME_FACE_RADIUS_UNITS] of a held-but-unseen slot as the same person,
 * publishing it under the original id (Lesson 037) so downstream per-face state keyed on
 * [FaceSnapshot.trackingId] is not reset mid-blink.
 */
class FaceRoster(private val maxFaces: Int, private val holdMs: Long) {

    /** One face the detector saw this frame: where it is, and how big (any consistent measure). */
    class Sighting(val snapshot: FaceSnapshot, val area: Float)

    /** One detection, reduced to what [assign] needs: who, and how big. */
    data class Candidate(val trackingId: Int, val area: Float)

    /** Every face seen within the last [holdMs], keyed by (canonical) tracking id, first-seen order. */
    private val held = LinkedHashMap<Int, HeldFace>()

    /** The faces holding a lens slot, in slot order. */
    private var slots: List<Int> = emptyList()

    /**
     * Detector ids folded into the id they were adopted under (new → original). A relabeled
     * person keeps reporting under the new id for as long as the detector tracks them, and every
     * one of those sightings must land on the original entry. Pruned when the original expires.
     */
    private val aliases = HashMap<Int, Int>()

    /** Scratch for the frame's candidates, reused. */
    private val candidates = ArrayList<Candidate>()

    /**
     * Folds one frame of detections in and returns the snapshots holding a slot, in slot order —
     * empty when nobody is in frame. The returned list is fresh each call and never mutated after.
     *
     * @param sightings the faces found this frame, in any order. Pass an empty list on a frame
     *   where detection failed; the hold still applies.
     * @param nowMs a monotonic clock, milliseconds.
     */
    fun update(sightings: List<Sighting>, nowMs: Long): List<FaceSnapshot> {
        for (sighting in sightings) {
            val snapshot = sighting.snapshot.rekeyed(aliases[sighting.snapshot.trackingId])
            held[snapshot.trackingId] =
                HeldFace(snapshot, seenAtMs = nowMs, area = sighting.area, fresh = true)
        }

        // Expire first, so a stale slot holder cannot be "the same person" as a newcomer. An
        // expired face takes its aliases with it: the next time one of those detector ids shows
        // up it is whoever the detector says it is.
        val expired = held.entries.iterator()
        while (expired.hasNext()) {
            val entry = expired.next()
            if (nowMs - entry.value.seenAtMs >= holdMs) {
                expired.remove()
                aliases.entries.removeAll { it.value == entry.key }
            }
        }

        adoptRedetectedFaces()

        candidates.clear()
        for ((id, face) in held) {
            // A face seen this frame competes for a free slot on its size. A face merely being
            // held keeps the slot it already has (assign keeps retained ids in order) but is offered
            // only if it has one — a held face without a slot must not take one.
            if (face.fresh) {
                candidates.add(Candidate(id, face.area))
            } else if (id in slots) {
                candidates.add(Candidate(id, area = 0f))
            }
            face.fresh = false
        }

        slots = assign(slots, candidates, maxFaces)
        return if (slots.isEmpty()) emptyList() else slots.mapNotNull { id -> held[id]?.snapshot }
    }

    /** Forgets every face. */
    fun clear() {
        held.clear()
        aliases.clear()
        slots = emptyList()
    }

    /**
     * Re-keys a held-but-unseen slot holder onto the fresh, slot-less face standing where it was —
     * the same person back under a new tracking id (see the class doc). The slot, its index, and
     * the id every downstream consumer knows this person by are all unchanged.
     */
    private fun adoptRedetectedFaces() {
        for (id in slots) {
            val holder = held[id] ?: continue
            if (holder.fresh) continue
            val successorId = findSuccessor(holder.snapshot) ?: continue
            val successor = held.remove(successorId) ?: continue
            held[id] = HeldFace(
                successor.snapshot.copy(trackingId = id),
                seenAtMs = successor.seenAtMs,
                area = successor.area,
                fresh = true,
            )
            aliases[successorId] = id
            // Belt and braces: anything that already pointed at the successor follows it.
            for (alias in aliases.entries) if (alias.value == successorId) alias.setValue(id)
        }
    }

    /**
     * The **nearest** fresh, slot-less face within [SAME_FACE_RADIUS_UNITS] of [holder], or
     * `null`. Nearest, not first-reported: two people cheek to cheek can both be relabeled in the
     * same frame, and first-match would let their identities cross.
     */
    private fun findSuccessor(holder: FaceSnapshot): Int? {
        val aspect = holder.sourceAspect
        val holderFrame = LensAnchor.faceFrame(holder, aspect) ?: return null
        val radius = SAME_FACE_RADIUS_UNITS * holderFrame.unit
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        for ((id, face) in held) {
            if (!face.fresh || id in slots) continue
            val frame = LensAnchor.faceFrame(face.snapshot, face.snapshot.sourceAspect) ?: continue
            // Distance between eye midpoints in square space, in units of the held face (Lesson
            // 032: measure in square space, in the snapshot's own frame).
            val dx = frame.originX - holderFrame.originX
            val dy = LensAnchor.toSquareY(frame.originY - holderFrame.originY, aspect)
            val distance = hypot(dx, dy)
            if (distance <= radius && distance < bestDistance) {
                best = id
                bestDistance = distance
            }
        }
        return best
    }

    private fun FaceSnapshot.rekeyed(canonicalId: Int?): FaceSnapshot =
        if (canonicalId == null || canonicalId == trackingId) this else copy(trackingId = canonicalId)

    /** One face's last good snapshot, when it was seen, and whether *this* frame saw it. */
    private class HeldFace(
        val snapshot: FaceSnapshot,
        val seenAtMs: Long,
        val area: Float,
        var fresh: Boolean,
    )

    companion object {
        /**
         * How far (in the held face's own eye-to-mouth units) a re-detected face may have moved
         * and still be the same person. A head does not travel a full face-length in the frame or
         * two the detector dropped; a second person cannot arrive on that spot in that time.
         */
        const val SAME_FACE_RADIUS_UNITS = 1f

        /**
         * The slot rule on its own: returns the tracking ids that hold a slot this frame, in slot
         * order — kept ids stay in place, gaps are filled by the largest [candidates], never more
         * than [maxFaces].
         *
         * @param slots the ids holding a slot last frame, in slot order.
         * @param candidates every face competing this frame, in any order.
         * @param maxFaces the cap. `0` or lower returns an empty roster.
         */
        fun assign(slots: List<Int>, candidates: List<Candidate>, maxFaces: Int): List<Int> {
            if (maxFaces <= 0 || candidates.isEmpty()) return emptyList()
            val present = HashSet<Int>(candidates.size)
            candidates.forEach { present.add(it.trackingId) }

            // Kept ids stay in their existing order so a face that has been lensed longest keeps
            // the same draw slot; ids the detector lost this frame fall out.
            val roster = ArrayList<Int>(maxFaces)
            for (id in slots) {
                if (id in present && id !in roster) roster.add(id)
                if (roster.size == maxFaces) return roster
            }

            // Free slots go to the largest untracked faces, largest first. Ties break on the lower
            // id so the choice is deterministic frame to frame.
            candidates
                .asSequence()
                .filter { it.trackingId !in roster }
                .distinctBy { it.trackingId }
                .sortedWith(compareByDescending<Candidate> { it.area }.thenBy { it.trackingId })
                .forEach { candidate ->
                    if (roster.size == maxFaces) return roster
                    roster.add(candidate.trackingId)
                }
            return roster
        }
    }
}
