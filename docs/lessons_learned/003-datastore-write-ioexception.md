# Lesson 003 — Wrap DataStore writes in try-catch(IOException)

## What went wrong

PR #5 called `userPreferencesRepository.setOnboardingCompleted(true)` inside `viewModelScope.launch { }` with no error handling. `dataStore.edit { }` can throw `IOException` on disk-full or file-corruption scenarios — and the coroutine would crash with an unhandled exception. The read path correctly handled IOException via `.catch { ... emit(emptyPreferences()) }`, but the write path was unprotected.

This is the kind of bug that only surfaces on devices with low storage or filesystem stress — exactly the conditions you can't easily reproduce in development.

## Pattern

Every DataStore write call inside a coroutine must be guarded:

```kotlin
viewModelScope.launch {
    try {
        repository.setSomePreference(value)
    } catch (e: IOException) {
        Log.e(TAG, "Failed to persist preference", e)
        // Decide on graceful degradation — usually non-fatal.
        // For onboarding state: user sees onboarding again next launch.
    }
}
```

Mirror this on the read side with `.catch { ... }`:

```kotlin
context.dataStore.data
    .catch { exception ->
        if (exception is IOException) {
            emit(emptyPreferences())
        } else {
            throw exception
        }
    }
    .map { preferences -> preferences[KEY] ?: defaultValue }
```

For unit testing: provide a `FailingWriteXRepository` fake that throws `IOException` on write, then assert state still transitions correctly when the write fails.

```kotlin
class FailingWritePreferencesRepository : UserPreferencesRepository {
    override val hasCompletedOnboarding: Flow<Boolean> = MutableStateFlow(false)
    override suspend fun setOnboardingCompleted(completed: Boolean) {
        throw IOException("Simulated disk full")
    }
}
```

## Detection checklist

- Grep for repository writes that touch DataStore — each call must be inside a try-catch for IOException (or its caller must catch it):
  ```
  grep -rn "repository\.set\|Repository\.set" app/src --include="*.kt"
  ```
- Every preferences-backed feature must have at least one test that exercises the IOException path.
- The read-side `.catch { if (it is IOException) ... else throw it }` must re-throw non-IOException — silently swallowing all exceptions hides real bugs.

## Reference

- [DataStore guide](https://developer.android.com/topic/libraries/architecture/datastore)
- PR #5 WARNING #6
