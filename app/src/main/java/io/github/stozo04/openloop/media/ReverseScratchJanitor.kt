package io.github.stozo04.openloop.media

import java.io.File

/**
 * Deletes pass-1 `_intermediate_*.mp4` scratch files left when reverse preview is cancelled or times
 * out. Does not remove trim-keyed cache outputs (`<sha1-hex>.mp4`).
 */
object ReverseScratchJanitor {

    const val INTERMEDIATE_PREFIX = "_intermediate_"

    data class CleanupResult(
        val deletedCount: Int,
        val bytesDeleted: Long,
    )

    /**
     * Removes intermediates under [scratchDir] plus any [trackedPaths] still on disk (e.g. wedged jobs
     * that never unwound [VideoReverser.reverse]).
     */
    fun cleanup(scratchDir: File, trackedPaths: Collection<String> = emptyList()): CleanupResult {
        var deletedCount = 0
        var bytesDeleted = 0L
        val seen = mutableSetOf<String>()

        fun deleteIfIntermediate(file: File) {
            val path = file.absolutePath
            if (!seen.add(path)) return
            if (!isDeletableIntermediate(file)) return
            val bytes = file.length().coerceAtLeast(0L)
            if (file.delete()) {
                deletedCount++
                bytesDeleted += bytes
            }
        }

        if (scratchDir.isDirectory) {
            scratchDir.listFiles()?.forEach { deleteIfIntermediate(it) }
        }
        trackedPaths.map { File(it) }.forEach { deleteIfIntermediate(it) }
        return CleanupResult(deletedCount, bytesDeleted)
    }

    /** True when [file] is a reverse pass-1 intermediate (not a sha1 cache-key output). */
    fun isDeletableIntermediate(file: File): Boolean {
        val name = file.name
        return name.startsWith(INTERMEDIATE_PREFIX) && name.endsWith(".mp4", ignoreCase = true)
    }
}
