# Verification performance measurements

Measured on September 6, 2026, from base commit `5c36f23`. Firebase PR
[#178](https://github.com/stozo04/OpenLoop/pull/178) was already merged when work began.
Its release configuration guard remains intact. The implementation uses the existing sweep,
Gradle cache, verifier runner, and shared adb helper.

## What the earlier numbers covered

[#175](https://github.com/stozo04/OpenLoop/pull/175) reported a 279.5-to-25.4-second improvement
for `-SkipConnected -SkipInspectCode`. That comparison excluded installed-app loops and
instrumented tests. Its full device sweep took 412.0 seconds, including 182.6 seconds of loops
and 183.4 seconds of connected testing. Its clean builds retained a populated local Gradle cache;
they were not first builds on an empty machine.

The preserved Firebase console log records 105.3 seconds for builds, lint, and JVM results,
295.5 seconds for five loops, and 205.2 seconds for instrumented tests. The later receipt instead
has `connected=false` and `onboardingLoop=skipped`. Those are different runs. Existing logs,
receipts, and report XML were copied and hashed before measurement.

## Comparable measurements

Windows, Gradle 9.7.1, AGP 9.3.2, and one Pixel 8 API-37 emulator were used. The emulator had
4 GiB RAM, software graphics, and snapshot loading disabled. Host comparisons ran without an
emulator. Cold means output-clean or cold-booted as specified; dependency and build caches remained
populated. These are single paired measurements, not statistical estimates.

| Check and equivalent scope                                                       | Before   | After    | Interpretation                                                                    |
| -------------------------------------------------------------------------------- | -------: | -------: | --------------------------------------------------------------------------------- |
| Warm debug/release assembly, lint, JVM results, test APK; report outputs cleared | 74.56 s  | 5.58 s   | 92.5% less elapsed time; JVM results reused from Gradle cache in both             |
| First output-clean build of the two modes, same tasks and populated cache        | 128.66 s | 117.84 s | No claim of an empty-cache build; the control also spent 4.06 s on mapping upload |
| Four current offline script checks                                               | 9.34 s   | 5.96 s   | All four executed; three independent workers replace serial execution             |
| Five loops, controlled cold boot and absent initial onboarding store             | 261.34 s | 271.31 s | 3.8% slower overall; no demonstrated cold-device speedup                          |
| Five loops, warm emulator after a completed full run                             | 240.02 s | 236.27 s | 1.6% less time; a modest result within visible emulator variability               |
| Manual launch readiness                                                          | 2.15 s   | 4.09 s   | A fixed sleep became Android's observed launch completion; not a speed win        |

The controlled cold loop pair used identical APK bytes:
`e4a02c2484a170b7fb67c4ce20698350d2a6179eba4293e512458ed65a0f82d7`.
All five loops passed in every comparison. The initial exploratory cold loop run took 378.19
seconds; its initial preferences were not controlled, so it is not used as the matched comparison.

The host bottleneck was a new Crashlytics release mapping ID on every invocation. The installed
3.0.8 plugin's `InjectMappingFileIdTask` makes normal release output out-of-date, which changes
linked resources and reruns R8. The controlled log records both invalidations. Verification mode
uses Firebase's supported
[`mappingFileUploadEnabled=false` setting](https://firebase.google.com/docs/crashlytics/android/get-deobfuscated-reports).
R8 and resource shrinking remain enabled. Normal shipping builds retain mapping uploads.

The untouched first baseline invoked the mapping-upload task. Subsequent controls excluded that
publication task; verification mode disables it directly. No app release was published.

## Where device time goes

The measurement probe observed existing subprocess calls and polling sleeps without replacing
their results. Parent loop durations are excluded from the following sums to avoid double-counting.
The small remainder includes Python, XML parsing, hashing, file writes, and process orchestration.

| Warm five-loop work                | Before                | After              |
| ---------------------------------- | --------------------: | -----------------: |
| APK installation                   | 4.73 s, five installs | 0 s, zero installs |
| Device artifact identity checks    | 0.56 s                | 1.17 s             |
| UI hierarchy dumps                 | 151.36 s              | 149.56 s           |
| Screenshots                        | 30.37 s, 17 images    | 32.42 s, 17 images |
| Polling sleeps                     | 36.33 s               | 34.83 s            |
| App starts and stops               | 2.44 s                | 2.20 s             |
| Permission grants                  | 0.48 s                | 0.32 s             |
| Logcat reads and clears            | 2.22 s                | 2.48 s             |
| Other adb actions and state checks | 10.59 s               | 11.56 s            |

In the controlled cold pair, five redundant installs consumed 14.75 seconds before the change.
The optimized run performed none, but extra UI polling outweighed that saving. The installation
reuse is verified; a broad claim that device execution became faster is not supported.

Cold boot was measured separately: 38.18 and 36.50 seconds to `sys.boot_completed=1`.
App preparation and shutdown are outside the loop timings. No host build ran concurrently with
these device comparisons, and no second controller drove the emulator.

The instrumented baseline took 137.41 seconds overall. Its JUnit report records 123.02 seconds
within the suite, leaving 14.39 seconds for build orchestration, installation, startup, and
collection. There were 123 tests, zero failures/errors, and one Samsung-only skip:
`VideoReverserTest.reverse_pass1SurvivesOnSamsung_afterPostTransformSettle`.
The remaining connected overhead was not individually attributable from that Gradle log;
it must not be presented as test execution or as installation alone.

A separate native runner experiment measured setup independently. It built the APKs in 3.56
seconds, installed the app in 1.42 seconds and the test APK in 0.43 seconds, then spent 116.08
seconds in `adb shell am instrument`. JUnit recorded 113.02 seconds inside that invocation.
The transcript has 123 scheduled cases, 122 passes, and one explicit assumption skip, with zero
failures. Its complete test-ID set matches the earlier connected XML. This characterizes costs;
it is not a replacement for the final Gradle-connected gate or a like-for-like speed comparison.

## Regression detection and isolation

No Kotlin test, installed-app assertion, deadline, recording-cap case, or non-keyframe-trim
precondition was removed. Existing polling around real dispatcher work stays. Scratch outputs
remain private to each test; reverse outputs are not shared between cases.

| Deliberate failure or invalid evidence                   | Detection                                                                                                |
| -------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| Temporary JVM assertion failure                          | Sweep returned nonzero; 648 tests, one failure; lint still reported; no green receipt                    |
| Camera selector label changed in a temporary APK         | Same app version, different APK hash; installer replaced the APK; real photo-mode loop failed in 12.18 s |
| UI source restored                                       | Source bytes restored, APK rebuilt and independently checked on the device; photo-mode loop passed       |
| Selected child exits 1                                   | Real temporary subprocess makes the runner fail and write a failed report                                |
| Empty discovery, unknown selection, or missing local APK | Runner rejects the request; an earlier success cannot remain the current result                          |
| Partial, failed, skipped, or wrong-APK loop receipt      | The sweep's real receipt predicate rejects it in the offline check                                       |
| JUnit summary disagrees with its testcase count          | Real PowerShell parser fails, including with the caller's error preference set to Continue               |
| Helper changes during a run                              | Runner rejects the result                                                                                |
| Installed APK hash differs after installation            | Shared installer fails instead of accepting the install command's exit code                              |
| Missing Firebase config                                  | Normal and verification release APK/bundle requests fail; config is restored byte-for-byte               |
| Verification bundle request with config present          | Fails before the bundle producer writes an AAB; the check asserts no new bundle exists                   |

Temporary application-source changes were restored. Rebuilding restored source did not reproduce
the earlier APK byte-for-byte, so the restored UI was verified on the newly built and installed
hash rather than assuming byte identity. The final committed-head sweep and test-ID comparison
are reported in the PR; benchmark receipts are not relabeled as final-head execution.

After deleting the JVM probe, the next host sweep restored exactly the original 647 test IDs,
zero failures, and zero skips in 32.20 seconds. Those results were explicitly `FROM-CACHE`.
The text-only sweep also rejected a deliberately broken link in an uncommitted Markdown edit
and wrote no green receipt.

## What moved and what stayed

- Normal release mapping publication moved out of routine verification builds. It remains part
  of the ordinary shipping build. Verification mode rejects bundle production.
- Identical APK installations are reused after checking actual device bytes. Every loop still
  grants its required permission, restarts its process, and performs its assertions and cleanup.
- Offline script checks run independently in parallel. Logs are collected by the parent.
- Local iteration can select named loops. Partial coverage lists its omissions and cannot satisfy
  the full sweep. Full PR validation still executes all shipped loops and all instrumented tests.
- A full sweep already supplies launch, onboarding, and screenshot proof. The instructions no
  longer demand equivalent manual runs before or after it.
- Link selection includes working-tree edits, so the cheap pre-commit text pass can catch broken
  links before committing and starting the full sweep.
- Gradle result reuse is labeled `FROM-CACHE` or `UP-TO-DATE`. `-RerunTests` requests actual
  JVM execution. Reports count skipped tests and preserve previous sweep logs.

No cross-commit device-result cache, test-selection framework, new dependency, or test deletion
was introduced. UI dumps, the real recording cap, Robolectric startup, and instrumented UI work
remain the main costs. Emulator results do not establish physical-device camera or codec behavior.
Android Studio Inspect Code remains a separate, explicitly reported gate.

Commands and risk policy live in
[Choose verification scope](../DEFINITION_OF_DONE.md#choose-verification-scope).
Local raw logs and call timings are preserved in the sibling `OpenLoop-verification-evidence`
directory. New sweeps retain history in `build/sweep-history/`, Gradle profiles in
`build/reports/profile/`, and a structured `build/sweep-receipt.json`.

![Returning-user camera proof from the warm full-loop benchmark](../e2e/2026-09-06-verification-speed.png)
