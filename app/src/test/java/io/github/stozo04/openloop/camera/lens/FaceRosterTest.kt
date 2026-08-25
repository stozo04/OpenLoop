package io.github.stozo04.openloop.camera.lens

import io.github.stozo04.openloop.camera.lens.FaceRoster.Candidate
import io.github.stozo04.openloop.camera.lens.FaceRoster.Sighting
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guard for [FaceRoster] — the "which faces wear the lens" rule from
 * `docs/PRD-multi-face-lenses.md` D2: locked slots, largest fills the gap.
 *
 * Like [LensPhysicsTest], this is the primary verification: an emulator's virtual scene is one
 * static poster and can never put a second face in front of the detector, so every property of
 * slot stability is asserted here or nowhere.
 */
class FaceRosterTest {

    private val two = 2

    /** The hold [FaceTracker] ships. */
    private val holdMs = 350L

    /** A normalized face at [centerX], the shape `LensAnchorTest` uses; eye-to-mouth is 0.2. */
    private fun face(id: Int, centerX: Float = 0.5f, eyeY: Float = 0.4f) = FaceSnapshot(
        leftEyeX = centerX - 0.08f,
        leftEyeY = eyeY,
        rightEyeX = centerX + 0.08f,
        rightEyeY = eyeY,
        mouthLeftX = centerX - 0.05f,
        mouthLeftY = eyeY + 0.2f,
        mouthRightX = centerX + 0.05f,
        mouthRightY = eyeY + 0.2f,
        sourceAspect = 1f,
        trackingId = id,
    )

    private fun seen(id: Int, centerX: Float = 0.5f, area: Float = 10f, eyeY: Float = 0.4f) =
        Sighting(face(id, centerX, eyeY), area)

    private fun List<FaceSnapshot>.ids() = map { it.trackingId }

    @Test
    fun emptyFrame_freesEverySlot() {
        assertEquals(emptyList<Int>(), FaceRoster.assign(listOf(1, 2), emptyList(), two))
    }

    @Test
    fun zeroCap_neverAssigns() {
        assertEquals(emptyList<Int>(), FaceRoster.assign(emptyList(), listOf(Candidate(1, 10f)), 0))
    }

    @Test
    fun firstFrame_takesTheLargestFaces_largestFirst() {
        val roster = FaceRoster.assign(
            slots = emptyList(),
            candidates = listOf(Candidate(3, 5f), Candidate(1, 20f), Candidate(2, 10f)),
            maxFaces = two,
        )

        assertEquals(listOf(1, 2), roster)
    }

    @Test
    fun aLockedFace_keepsItsSlot_whenALargerFaceArrives() {
        // The whole point of the lock: a bystander walking closer must not steal the broccoli.
        val roster = FaceRoster.assign(
            slots = listOf(1, 2),
            candidates = listOf(Candidate(1, 5f), Candidate(2, 6f), Candidate(9, 100f)),
            maxFaces = two,
        )

        assertEquals(listOf(1, 2), roster)
    }

    @Test
    fun slotOrder_isStableFrameToFrame() {
        // Slot order is draw order; two faces must not swap layers because their sizes crossed.
        val roster = FaceRoster.assign(
            slots = listOf(1, 2),
            candidates = listOf(Candidate(2, 50f), Candidate(1, 5f)),
            maxFaces = two,
        )

        assertEquals(listOf(1, 2), roster)
    }

    @Test
    fun aFreedSlot_goesToTheLargestUntrackedFace() {
        // Face 1 left; 7 and 8 are both waiting, 8 is bigger.
        val roster = FaceRoster.assign(
            slots = listOf(1, 2),
            candidates = listOf(Candidate(2, 10f), Candidate(7, 8f), Candidate(8, 12f)),
            maxFaces = two,
        )

        assertEquals(listOf(2, 8), roster)
    }

    @Test
    fun capIsRespected_withMoreFacesThanSlots() {
        val roster = FaceRoster.assign(
            slots = emptyList(),
            candidates = (1..5).map { Candidate(it, it.toFloat()) },
            maxFaces = two,
        )

        assertEquals(two, roster.size)
        assertEquals(listOf(5, 4), roster)
    }

    @Test
    fun tiesOnSize_breakOnTheLowerId_soTheChoiceIsDeterministic() {
        val roster = FaceRoster.assign(
            slots = emptyList(),
            candidates = listOf(Candidate(4, 10f), Candidate(2, 10f), Candidate(3, 10f)),
            maxFaces = two,
        )

        assertEquals(listOf(2, 3), roster)
    }

    @Test
    fun aSingleSlot_isTheOriginalSingleFaceLock() {
        // maxFaces = 1 must reproduce the behavior lenses shipped with: lock on the most
        // prominent face, hold it against a larger newcomer, re-take only when it is gone.
        val locked = FaceRoster.assign(emptyList(), listOf(Candidate(1, 5f), Candidate(2, 3f)), 1)
        assertEquals(listOf(1), locked)

        val held = FaceRoster.assign(locked, listOf(Candidate(1, 5f), Candidate(2, 50f)), 1)
        assertEquals(listOf(1), held)

        val retaken = FaceRoster.assign(held, listOf(Candidate(2, 50f), Candidate(3, 4f)), 1)
        assertEquals(listOf(2), retaken)
    }

    @Test
    fun duplicateIds_inOneFrame_occupyOneSlot() {
        // Belt and braces: a detector must not be able to fill both slots with one person.
        val roster = FaceRoster.assign(
            slots = emptyList(),
            candidates = listOf(Candidate(1, 10f), Candidate(1, 10f), Candidate(2, 1f)),
            maxFaces = two,
        )

        assertEquals(listOf(1, 2), roster)
    }

    // ---------------------------------------------------------------- the hold (stateful)

    @Test
    fun aBlink_isHeld_perFace() {
        val roster = FaceRoster(two, holdMs)
        roster.update(listOf(seen(1, 0.3f), seen(2, 0.7f)), nowMs = 0)

        // Face 1 misses a frame; face 2 is still there.
        val held = roster.update(listOf(seen(2, 0.7f)), nowMs = 33)

        assertEquals(listOf(1, 2), held.ids())
    }

    @Test
    fun theHold_expires_andFreesTheSlot() {
        val roster = FaceRoster(two, holdMs)
        roster.update(listOf(seen(1, 0.3f), seen(2, 0.7f)), nowMs = 0)

        assertEquals(listOf(1, 2), roster.update(listOf(seen(2, 0.7f)), nowMs = holdMs - 1).ids())
        assertEquals(listOf(2), roster.update(listOf(seen(2, 0.7f)), nowMs = holdMs).ids())
    }

    @Test
    fun aDetectionFailure_isJustAFrameWithNoSightings() {
        val roster = FaceRoster(two, holdMs)
        roster.update(listOf(seen(1)), nowMs = 0)

        assertEquals(listOf(1), roster.update(emptyList(), nowMs = 100).ids())
        assertEquals(emptyList<Int>(), roster.update(emptyList(), nowMs = 100 + holdMs).ids())
    }

    @Test
    fun aHeldFace_withoutASlot_cannotTakeOne() {
        // Three people: 3 is the bystander without a slot. Then 1 leaves and 3 misses the same
        // frame — 3 must not be handed 1's slot on the strength of a held snapshot.
        val roster = FaceRoster(two, holdMs)
        roster.update(listOf(seen(1, 0.2f, area = 30f), seen(2, 0.5f, area = 20f), seen(3, 0.8f, area = 10f)), 0)

        val next = roster.update(listOf(seen(2, 0.5f, area = 20f)), nowMs = 33)

        assertEquals(listOf(1, 2), next.ids())
    }

    @Test
    fun aThirdPerson_cannotTakeASlot_duringSomeonesBlink() {
        // Success criterion 4 with the hold in play: 1 blinks, 9 walks in somewhere else.
        val roster = FaceRoster(two, holdMs)
        roster.update(listOf(seen(1, 0.3f), seen(2, 0.7f)), nowMs = 0)

        val next = roster.update(listOf(seen(2, 0.7f), seen(9, 0.9f, area = 100f)), nowMs = 33)

        assertEquals(listOf(1, 2), next.ids())
    }

    // ---------------------------------------------------------------- id churn

    @Test
    fun aFaceRedetectedUnderANewId_keepsItsSlotAndItsOriginalId_soloCase() {
        // The regression a review caught: one person, the detector drops a frame and comes back
        // with a new id. Old code: the fresh detection won outright. This must not draw two lenses
        // on one face for 350 ms, and must not lock the new id out — and the person must keep the
        // id every downstream per-face state (springs, eased mouth) is keyed on. Only the geometry
        // is the new detection's.
        val roster = FaceRoster(two, holdMs)
        roster.update(listOf(seen(1, 0.5f)), nowMs = 0)
        roster.update(emptyList(), nowMs = 33)

        val back = roster.update(listOf(seen(7, 0.52f)), nowMs = 66)

        assertEquals(listOf(1), back.ids())
        assertEquals(0.52f - 0.08f, back.single().leftEyeX, 1e-6f)
    }

    @Test
    fun aFaceRedetectedUnderANewId_keepsItsSlotAndItsOriginalId_withTheOtherFacePresent() {
        val roster = FaceRoster(two, holdMs)
        roster.update(listOf(seen(1, 0.3f), seen(2, 0.7f)), nowMs = 0)
        roster.update(listOf(seen(2, 0.7f)), nowMs = 33)

        val back = roster.update(listOf(seen(2, 0.7f), seen(7, 0.31f)), nowMs = 66)

        // Slot ORDER and ids are kept: 7 is 1 come back, 2 stays where it was.
        assertEquals(listOf(1, 2), back.ids())
        assertEquals(0.31f - 0.08f, back.first().leftEyeX, 1e-6f)
    }

    @Test
    fun theNewId_staysFoldedOntoTheOriginal_onEveryLaterFrame() {
        // ML Kit keeps reporting the relabeled person under the new id for as long as it tracks
        // them; every one of those sightings must land on the original entry, not fork a second
        // face next to it.
        val roster = FaceRoster(two, holdMs)
        roster.update(listOf(seen(1, 0.5f)), nowMs = 0)
        roster.update(emptyList(), nowMs = 33)
        roster.update(listOf(seen(7, 0.52f)), nowMs = 66)

        val later = roster.update(listOf(seen(7, 0.54f)), nowMs = 99)

        assertEquals(listOf(1), later.ids())
        assertEquals(0.54f - 0.08f, later.single().leftEyeX, 1e-6f)
    }

    @Test
    fun anAlias_diesWithTheFaceItPointedAt() {
        // Once the person is gone for longer than the hold, a detector id that used to mean them
        // means whoever the detector says it does — a new arrival is not silently renamed.
        val roster = FaceRoster(two, holdMs)
        roster.update(listOf(seen(1, 0.5f)), nowMs = 0)
        roster.update(emptyList(), nowMs = 33)
        roster.update(listOf(seen(7, 0.52f)), nowMs = 66)

        roster.update(emptyList(), nowMs = 66 + holdMs)
        val fresh = roster.update(listOf(seen(7, 0.52f)), nowMs = 66 + holdMs + 33)

        assertEquals(listOf(7), fresh.ids())
    }

    @Test
    fun adoption_picksTheNearestCandidate_notTheFirstReported() {
        // Two fresh faces both inside the radius, reported farthest-first. The nearer one is the
        // person come back; the other is a newcomer who takes the free slot on its own id.
        val roster = FaceRoster(two, holdMs)
        roster.update(listOf(seen(1, 0.5f)), nowMs = 0)

        val next = roster.update(listOf(seen(8, 0.65f), seen(7, 0.55f)), nowMs = 33)

        assertEquals(listOf(1, 8), next.ids())
        assertEquals(0.55f - 0.08f, next.first().leftEyeX, 1e-6f)
    }

    @Test
    fun aNewFace_farFromTheHeldOne_isNotMistakenForIt() {
        // 1 blinks; 9 appears a full frame-width away. That is a newcomer, not 1 come back — and
        // with both slots held it waits, exactly like the third-person case.
        val roster = FaceRoster(two, holdMs)
        roster.update(listOf(seen(1, 0.2f), seen(2, 0.5f)), nowMs = 0)

        val next = roster.update(listOf(seen(2, 0.5f), seen(9, 0.9f)), nowMs = 33)

        assertEquals(listOf(1, 2), next.ids())
    }

    @Test
    fun adoption_needsTheFaceToHaveMovedLessThanOneFaceUnit() {
        // Eye-to-mouth is 0.2 here. Just inside adopts; just outside does not.
        val inside = FaceRoster(two, holdMs)
        inside.update(listOf(seen(1, 0.5f)), nowMs = 0)
        assertEquals(listOf(1), inside.update(listOf(seen(7, 0.5f + 0.19f)), nowMs = 33).ids())

        val outside = FaceRoster(two, holdMs)
        outside.update(listOf(seen(1, 0.5f)), nowMs = 0)
        assertEquals(listOf(1, 7), outside.update(listOf(seen(7, 0.5f + 0.21f)), nowMs = 33).ids())
    }

    @Test
    fun clear_forgetsEveryone() {
        val roster = FaceRoster(two, holdMs)
        roster.update(listOf(seen(1), seen(2, 0.9f)), nowMs = 0)

        roster.clear()

        assertEquals(emptyList<Int>(), roster.update(emptyList(), nowMs = 10).ids())
    }
}
