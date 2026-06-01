package io.github.stozo04.openloop.work

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Abstraction over WorkManager so [io.github.stozo04.openloop.ui.OpenLoopViewModel] stays testable
 * without Robolectric (Lesson 004 / Issue #40).
 */
interface BoomerangRenderScheduler {

    /** Enqueue a unique render job; returns the work id for progress/completion observation. */
    fun enqueue(request: BoomerangRenderRequest): UUID

    /** Render progress 0f..1f while the job is running. */
    fun observeProgress(workId: UUID): Flow<Float>

    /** Exactly one terminal [BoomerangRenderWorkResult] per [workId]. */
    fun observeResult(workId: UUID): Flow<BoomerangRenderWorkResult>

    /** P2 cancel coordination — cancels in-flight render for [scratchUuid]. */
    fun cancelRenderWork(scratchUuid: String)
}
