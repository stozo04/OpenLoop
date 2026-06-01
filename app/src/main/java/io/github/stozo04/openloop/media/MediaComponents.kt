package io.github.stozo04.openloop.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import io.github.stozo04.openloop.data.VideoStorageRepository
import io.github.stozo04.openloop.data.VideoStorageRepositoryImpl
import java.io.File

/**
 * Shared wiring for on-device media + storage, used by [io.github.stozo04.openloop.MainActivity]
 * and [io.github.stozo04.openloop.work.BoomerangRenderWorker]. Keeps [Context] out of the
 * ViewModel (Lesson 004) while avoiding duplicated Factory logic.
 */
object MediaComponents {

    @OptIn(UnstableApi::class)
    fun buildVideoProcessor(context: Context): VideoProcessor =
        Media3VideoProcessor(
            context = context.applicationContext,
            reverser = VideoReverser(
                scratchDir = File(context.applicationContext.cacheDir, "scratch/reversed"),
            ),
        )

    fun buildVideoStorageRepository(context: Context): VideoStorageRepository =
        VideoStorageRepositoryImpl(
            cacheDir = context.applicationContext.cacheDir,
            filesDir = context.applicationContext.filesDir,
        )
}
