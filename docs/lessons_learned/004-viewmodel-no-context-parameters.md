# Lesson 004 — Never pass Context to ViewModel methods; extract a Repository

## What was flagged

`OpenLoopViewModel` accepts `Context` as a parameter in `startBurstCapture`, `loadRecordedVideos`, `deleteVideo`, and `navigateToGallery`. While passing Context per-call is less dangerous than holding it as a field, it still:

- Couples the ViewModel to the Android framework — every test must mock Context.
- Violates Google's ViewModel guideline: "A ViewModel must never reference a view, Lifecycle, or any class that may hold a reference to the activity context."
- Compounds as the app grows — every new Context-dependent operation extends the pattern. Phase 3 video processing would add several more.

This was pre-existing in OpenLoop, but every PR that extends the pattern makes the eventual refactor larger.

## Pattern

- Inject only path-likes, primitives, or repositories into the ViewModel — never Context itself.
- For filesystem operations, extract a repository interface:

  ```kotlin
  interface VideoStorageRepository {
      fun loadRecordedVideos(): List<RecordedVideo>
      fun saveVideo(temp: File): File?
      fun deleteVideo(video: RecordedVideo)
  }

  class VideoStorageRepositoryImpl(
      private val videosDir: File,
      private val thumbnailsDir: File,
      private val cacheDir: File,
  ) : VideoStorageRepository { /* ... */ }
  ```

- The ViewModel depends on the interface, not Context:

  ```kotlin
  class OpenLoopViewModel(
      private val userPreferences: UserPreferencesRepository,
      private val videoStorage: VideoStorageRepository,
  ) : ViewModel()
  ```

- The `Factory` (constructed in MainActivity) is the right place to bridge from Context to repositories:

  ```kotlin
  class Factory(private val context: Context) : ViewModelProvider.Factory {
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
          val videoStorage = VideoStorageRepositoryImpl(
              videosDir = File(context.filesDir, "videos"),
              thumbnailsDir = File(context.filesDir, "thumbnails"),
              cacheDir = context.cacheDir,
          )
          return OpenLoopViewModel(userPrefs, videoStorage) as T
      }
  }
  ```

- Tests then use fakes that hold an in-memory `MutableList<RecordedVideo>` — no mocking Context or File.

## Detection checklist

- ViewModel files must not contain `: Context` or `Context,` in any function signature:
  ```
  grep -rn ": Context\|Context," app/src/main/java/com/OpenLoop/app/ui/*ViewModel*.kt
  ```
- The only Context references in ViewModel code should be inside `Factory` creation — and even there, only to construct the repositories that get passed in.
- Every ViewModel test should be possible without `mockk<Context>()`.

## Reference

- [ViewModel Overview](https://developer.android.com/topic/libraries/architecture/viewmodel) — "A ViewModel must never reference a view, Lifecycle, or any class that may hold a reference to the activity context."
- PR #5 FAIL #3
