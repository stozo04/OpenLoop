# Lesson 029 — A test's premise must be enforced, not hoped for: park the worker, don't drop its runnable

> Origin: `RenderCancellationRobolectricTest` failing `expected:<CANCELLED> but was:<FAILED>` at a rate
> of ~1 in 5 **full-suite** runs (2026-08-07), while passing 3/3 in isolation. Surfaced by a cleanup
> PR that added five unrelated tests — enough of a timing shift to expose it.

## What went wrong

The test needed WorkManager work that is still cancellable, so it configured the worker executor to
throw the work away:

```kotlin
Configuration.Builder()
    .setExecutor { /* no-op */ }   // WorkerWrapper runnable is dropped, so doWork() never runs
    .setTaskExecutor { it.run() }  // internal ops inline
```

"The worker never runs" was a **hope about internals**, not an enforced condition. WorkManager still
transitions the WorkSpec toward RUNNING and hands a `WorkerWrapper` to that executor; whether any part
of it resolves — and whether that resolution races the `CANCELLED` write from `cancelUniqueWork` — is
timing-dependent. When the race lost, the WorkSpec resolved to **FAILED** and the assertion blew up in
a file nobody had touched.

Two properties made it expensive: it **passed in isolation** (so the obvious `--tests` re-run cleared
it), and it failed in a module unrelated to the change under review (so the first instinct is "my edit
broke the render pipeline" — it hadn't).

## Pattern

**Make the state you are testing real, then hold it there.** Substitute a worker that reaches the
state and parks, instead of arranging for no worker to run:

```kotlin
Configuration.Builder()
    .setWorkerFactory(object : WorkerFactory() {
        override fun createWorker(ctx: Context, name: String, params: WorkerParameters) =
            object : CoroutineWorker(ctx, params) {
                override suspend fun doWork(): Result {
                    started.countDown()
                    awaitCancellation()   // genuinely RUNNING until cancelled
                }
            }
    })
    .setTaskExecutor { it.run() }
```

The test then **waits on the latch** before cancelling, so "the work is in flight" is a fact it
verified, not a side effect it assumed. Bonus: cancelling a *running* worker is the real user-cancel
path, so the test also got more faithful — the fix strengthened coverage rather than relaxing it.

More generally: if a test's setup comment explains why some framework internal *won't* happen, that
sentence is the bug. Enforce it with a latch, a constraint, or a substituted collaborator.

## Detection checklist

- Grep test configs for no-op executors / swallowed runnables:
  `grep -rn "setExecutor {" app/src/test` — each hit is a premise resting on internals.
- A test that **passes in isolation but fails in the full suite** is order/timing-dependent; re-running
  it alone proves nothing. Reproduce with the whole suite (`--rerun-tasks`, several times) and count.
- Never "fix" this class of flake by widening the assertion (accepting FAILED) or adding a retry —
  that relocates the fragility. Remove the dependence on internals.
- A failure in a module the change never touched: verify against a clean checkout of `main` before
  suspecting your own diff (here `main` was green, which correctly redirected the search to *timing*,
  not *behavior*).

## Reference

- [`WorkerFactory`](https://developer.android.com/reference/androidx/work/WorkerFactory) — the supported
  seam for substituting workers in tests.
- [Testing Worker implementations](https://developer.android.com/topic/libraries/architecture/workmanager/how-to/testing)
  — `WorkManagerTestInitHelper`, custom `Configuration`.
- `work/RenderCancellationRobolectricTest.kt`. Related: [[008-jvm-test-file-and-dispatcher-pitfalls]]
  (a green IDE state hiding a suite that doesn't actually run clean).
