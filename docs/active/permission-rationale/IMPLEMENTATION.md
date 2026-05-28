# Permission Rationale Flow (Issue #11)

## Problem statement

`MainActivity.checkPermissions()` was a 2-state flow: granted → proceed, otherwise → launch the
system dialog. The middle state from Google's recommended permission flow — show an educational
rationale before re-asking — was missing. Consequences: users who denied once get re-prompted with
no context (more likely to deny again), and users who permanently denied see a button that appears
broken because the launcher returns immediately with `granted = false`.

Origin: WARNING #5 from PR #5 review; captured in
[`docs/lessons_learned/006-permission-rationale-flow.md`](../../lessons_learned/006-permission-rationale-flow.md).

## Scope

**In:**
- New `OpenRangUiState.PermissionRationale` state.
- ViewModel `showPermissionRationale()` and `onRationaleAcknowledged()`.
- `MainActivity.checkPermissions()` rewritten as a 3-state `when` (granted / rationale / request).
- `PermissionDeniedScreen` generalized into `PermissionExplanationScreen`, reused for both the
  rationale and permanent-denial UIs via a parameterized secondary action.
- "Not now" cancel affordance on the rationale screen (`onRationaleDeclined()` → `PermissionDenied`),
  per Google's "always provide the option to cancel an educational UI flow" guidance.
- Unit tests for the new transitions, plus a Compose UI test for `PermissionExplanationScreen`.

**Out (tracked separately):** DataStore-backed permission UX metadata (#6); `targetSdk` bump (#7);
removing `Context` params from the ViewModel (#10).

## Architecture

OS-only state — `shouldShowRequestPermissionRationale()` is tracked by Android, so no DataStore is
needed. The rationale "Grant" action launches the system dialog **directly** (bypassing
`checkPermissions()`); routing back through `CheckingPermissions` alone would re-enter the rationale
branch and loop, because `shouldShowRequestPermissionRationale()` stays `true` until the user
actually responds. See `docs/PRD-mission-control.md` §3 (State Machine) and §4.7 (MainActivity).

## Implementation steps

1. `OpenRangUiState.kt` — add `object PermissionRationale`.
2. `OpenRangViewModel.kt` — add `showPermissionRationale()` → `PermissionRationale` and
   `onRationaleAcknowledged()` → `CheckingPermissions`.
3. `MainActivity.kt` — `requiredPermissions` array; 3-state `checkPermissions()`;
   `onRationaleAcknowledged()` (acknowledge + direct launch); route `PermissionRationale` and
   `PermissionDenied` through `PermissionExplanationScreen`.
4. `PermissionExplanationScreen` — parameterized `title` / `body` / `primaryActionLabel` /
   `onPrimaryAction` / nullable `onOpenSettings`.

## Testing plan

Unit tests (`OpenRangViewModelTest.kt`):
- `showPermissionRationale transitions to PermissionRationale`
- `onRationaleAcknowledged transitions to CheckingPermissions`
- `onRationaleDeclined transitions to PermissionDenied`
- `rationale flow ending in grant reaches ReadyToCapture`
- `rationale flow ending in denial reaches PermissionDenied`

Compose UI tests (`PermissionExplanationScreenTest.kt`): rationale variant shows "Grant"/"Not now"
and hides Settings; denial variant shows "Try Again"/"Open Device Settings"; primary-only variant
hides the secondary button; both buttons fire their callbacks.

The Activity-level `checkPermissions()` branching (framework calls) is verified by manual emulator
passes per the issue's acceptance criteria — Activities are out of scope for local unit tests
(`docs/TEST_COVERAGE.md`).

Note: while adding coverage, the committed `OpenRangViewModelTest.kt` was found to never compile or
run green; the fixes and the general pattern are recorded in
[`docs/lessons_learned/008-jvm-test-file-and-dispatcher-pitfalls.md`](../../lessons_learned/008-jvm-test-file-and-dispatcher-pitfalls.md).

## Acceptance criteria

- [x] `PermissionRationale` added to the sealed interface.
- [x] `showPermissionRationale()` / `onRationaleAcknowledged()` added.
- [x] `checkPermissions()` rewritten as a 3-state `when`.
- [x] `PermissionExplanationScreen` serves both rationale and denial.
- [x] Rationale screen has a "Not now" cancel affordance (`onRationaleDeclined()` → `PermissionDenied`).
- [x] Routing wired for both states.
- [x] Unit tests for the new transitions; full suite green (24 unit tests).
- [x] Compose UI test for `PermissionExplanationScreen` (rationale vs denial variants).
- [x] PRD state-machine table updated with the `PermissionRationale` row.
- [ ] Manual emulator pass (fresh install / deny-once / grant-from-rationale / permanent-deny).
