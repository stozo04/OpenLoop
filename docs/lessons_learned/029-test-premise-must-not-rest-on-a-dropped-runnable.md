# Lesson 029 — A test's premise must be enforced, not hoped for: park the worker, don't drop its runnable

> Origin: `RenderCancellationRobolectricTest` failing `expected:<CANCELLED> but was:<FAILED>` at a rate
> of ~1 in 5 **full-suite** runs (2026-08-07), while passing 3/3 in isolation. Surfaced by a cleanup
> PR that added five unrelated tests — enough of a timing shift to expose it.

## What went wrong

The test needed WorkManager work that is still cancellable, so it configured the worker executor to
discard the work:

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
                    awaitCancellation()   // genuinely RUNNING until canceled
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

## The second shape: `advanceUntilIdle()` across a real-dispatcher hop

> Added 2026-08-09 (PR #118). Same rule, different disguise — and this one is far more common in
> this repo than the dropped-runnable original.

`OpenLoopViewModel.ensureReversedSegment` deliberately runs the reverse inside
`withContext(Dispatchers.IO)` on the JVM-test path (it avoids a Main/Unconfined deadlock). That is a
**real thread pool**, not the `TestDispatcher`. So this test was asserting on a counter that another
thread had been asked to increment microseconds earlier:

```kotlin
viewModel.onNextFromTrim()
advanceUntilIdle()                                        // drains VIRTUAL time only
assertEquals(1, fakeVideoProcessor.ensureReversedCount)   // expected:<1> but was:<0>
```

`advanceUntilIdle()` drains the test scheduler. It knows nothing about `Dispatchers.IO` and cannot
wait for it. The assertion passed only because the pool is normally idle and picks the task up
instantly — under full-suite CPU contention it does not, and the test fails in a file the change
never touched. It passed 100% in isolation, so the reflexive `--tests` re-run cleared it every time.

Two things made it invisible for months: the counter was also a **plain `Int` written on a pool
thread and read from the test thread** (no happens-before edge, so even a *late* increment might not
have been visible), and every sibling assertion on the same counter happened to sit behind
`awaitEditorReverseReady()` / `awaitReverseSettled { }` — helpers this repo already built for exactly
this hazard. Only the one test that deliberately *gates* the reverse open couldn't use the
"wait until ready" helper, and it reached for a bare `advanceUntilIdle()` instead.

**Pattern:** when the code under test hops to a real dispatcher, wait for the *arrival*, and make the
observed field `@Volatile` so the wait can see it:

```kotlin
viewModel.onNextFromTrim()
awaitReverseSettled { fakeVideoProcessor.ensureReversedCount == 1 }   // enforce the premise
assertEquals(1, fakeVideoProcessor.ensureReversedCount)
```

## Detection checklist

- Grep test configs for no-op executors / swallowed runnables:
  `grep -rn "setExecutor {" app/src/test` — each hit is a premise resting on internals.
- **Grep for the dispatcher-hop shape:** any `advanceUntilIdle()` / `runCurrent()` immediately
  followed by an assertion on state that production code mutates behind `withContext(Dispatchers.IO)`
  (or `Dispatchers.Default`, or a bare `Executor`). `rg -n "Dispatchers.IO" app/src/main` lists the
  hops; every test asserting past one must wait on a condition, not on virtual time.
- Any fake field written from a pool thread and read by a test must be `@Volatile` (or atomic) —
  a spin-wait on a plain `Int` is not guaranteed to terminate.
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
