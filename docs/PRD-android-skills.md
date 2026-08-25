# PRD — Google Android Skills Adoption

**Status:** Approved 2026-08-21 with one modification — owner overruled curation; install **all 21 skills** via the official plugin (see Decision)
**Issue:** [#139 — Add Google Skills](https://github.com/stozo04/OpenLoop/issues/139)
**Date:** 2026-08-21
**Upstream:** [`android/skills`](https://github.com/android/skills) @ `6685cac2923e` (2026-08-17), Apache 2.0

## Problem statement

Agent sessions in this repo rely on model training data that can be a year stale — which is
why `CLAUDE.md` forces a `developer.android.com` search before any claim about Android
behavior. Google ships that same knowledge today as **agent skills**: 21 evaluated, versioned
`SKILL.md` packages (the open standard OpenLoop's own `.claude/skills/` already uses),
deliberately focused on "use cases and workflows where evaluations show LLMs underperform."
Several target OpenLoop's exact domains: CameraX recording lifecycles, R8/keep-rule
optimization, Play policy compliance, foldables.

Adopting the right ones grounds every future session for free. Adopting the wrong ones is an
active hazard: skills **auto-trigger by description match**, so an installed skill that
contradicts this repo's architecture will steer sessions against it.

## Success criteria

1. Installed skills auto-trigger during matching domain work (verified in a live session
   after restart).
2. The r8-analyzer flow produces an actionable keep-rule/app-size report on the 31 MB release bundle.
3. No skill recommendation against a documented repo decision survives — the `CLAUDE.md`
   precedence section catches it.
4. All existing gates stay green (Markdown placement, cspell, lint).

## Decision: all 21 skills via the official plugin (owner, 2026-08-21)

The draft recommended vendoring a curated subset into `.claude/skills/` to keep
architecture-conflicting skills (chiefly `navigation-3`) out of the auto-trigger pool. **The
owner overruled curation: install everything, no cherry-picking.** With curation off the
table, the official Claude Code plugin is the strictly better mechanism — one settings
entry, Google maintains updates, nothing vendored to drift:

```json
// .claude/settings.json (checked in) — written by:
//   claude plugin install android-skills@android-skills --scope project
{
  "extraKnownMarketplaces": {
    "android-skills": { "source": { "source": "github", "repo": "android/skills" } }
  },
  "enabledPlugins": { "android-skills@android-skills": true }
}
```

`enabledPlugins` is an object map (`{"name@marketplace": true}`), not an array — the array form in
the first draft of this PRD was wrong. Notes: a session restart activates it; first launch after
the change shows the standard folder-trust prompt once (a project-level `extraKnownMarketplaces`
applies only after the folder is trusted). Updates ride the marketplace — no provenance
bookkeeping needed. **"Self-contained on a fresh clone" means the marketplace *name resolves*, not
that the skills *load*:** since Claude Code v2.1.195 an externally-sourced plugin enabled only by the
project file doesn't load until `claude plugin install android-skills@android-skills --scope project`
has been run once on that machine (omit `--scope project` and it lands at user scope — the drift
plan step 2 undid). Procedure and citation: [`guides/android-skills.md`](guides/android-skills.md) →
"Scope: this project only".

**Scope: project only, never user.** Every turn, Claude Code injects each enabled skill's name +
description into the prompt — the 21 android-skills descriptions measure ~8 kB (≈2 k tokens) per
turn. Enabled at user scope that tax lands in every project on the machine, and the precedence
guard (which lives in *this* repo's `CLAUDE.md`) protects none of them. The 2026-08-21 install had
landed at **user** scope (`~/.claude/settings.json`); it was migrated to project scope on 2026-08-22
— plan step 2 records the evidence. How to invoke, scope, and update the skills:
[`guides/android-skills.md`](guides/android-skills.md).

**Mitigation for the overruled concern — precedence guard in `CLAUDE.md`:** since plugin
skills can't be edited, the guard lives in `CLAUDE.md` ("Google Android Skills —
Precedence"), which every session reads first: repo docs win over skill guidance, with the
known collision points spelled out (`navigation-3` vs the Lesson-014 sealed NavHost;
`camerax`'s `MlKitAnalyzer` preference vs the deliberate manual `FaceTracker` analyzer;
AGP-version suggestions vs the installed-Studio ceiling).

## Triage — all 21 skills

> All 21 are installed (decision above). The tiers below no longer gate *installation* —
> they record which skills we expect to pull weight, which need a pilot before we lean on
> their output, and which are guarded or expected to stay dormant.

### High value (expected to trigger and help)

| Skill                     | Why (verified against the skill body, not just its description)                                                                                                                                                                                                                                                                                                                                                                                                                             |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `camera/camerax`          | Core domain. Body read in full: recording-lifecycle pitfalls (`prepareRecording().withAudioEnabled()` immutability — we record video), `Camera2Interop` blueprints for manual controls, and references for **foldables** (our Fold lanes), **thermals** (long recordings), **ML Kit spatial** (coordinate mapping + mirrored lens — `LensAnchor`'s exact problem), and **testing** (fakes over mocks — matches `TEST_COVERAGE.md`). Current: last-updated 2026-08-06, CameraX-1.6-era APIs. |
| `performance/r8-analyzer` | Body read in full. AGP 9.3.1 unlocks its quantitative Path A: `./gradlew :app:analyzeReleaseR8Config` → Python analysis → scored keep-rule report in `tmp/keepradius/` (gitignored, like play-policy-insights' `.scratch/`). Direct lever on the 31 MB bundle; becomes a release-cadence step.                                                                                                                                                                                              |

### Pilot first (run once before trusting its output — pilot approved 2026-08-21)

| Skill                       | Why the caution                                                                                                                                                                                                                                                                                |
| --------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `play/play-policy-insights` | Not markdown — a ~100 kB Python harness (orchestrator, scanner, Play Store scraper) generating compliance reports against Permissions/Data-Safety policy domains. High value for our live Play presence (`docs/play-store/`), but its report earns trust by one pilot run, not by description. |

### Audit once (skill stays installed; the audit is a one-time task)

| Skill                   | Why                                                                                                                                                             |
| ----------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `system/edge-to-edge`   | One-time migration check against targetSdk 36; a camera-first UI is mostly there already. Run the audit, fix gaps, done.                                        |
| `testing/testing-setup` | We already have a testing strategy (`docs/TEST_COVERAGE.md`); its references assume Hilt, which we don't use. Skim once for gaps (screenshot testing patterns). |

### Later (leverage when the domain work starts)

| Skill                              | Trigger to adopt                                                                                                                                                                                                                                                 |
| ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `jetpack-compose/adaptive`         | First real tablet/Fold-posture layout work. Caution: its references embed Navigation 3 recipes — use the layout guidance, ignore the nav recipes.                                                                                                                |
| `profilers/android-profiler`       | First perf investigation that needs Perfetto (encoder churn, jank in the media pipeline).                                                                                                                                                                        |
| `security/android-intent-security` | Next feature touching share/import intents (`VideoImporter`, share sheet, MediaStore publish) — run as an audit then.                                                                                                                                            |
| `jetpack-compose/theming/styles`   | Next design-system pass over `ui/theme/` + `ui/components/`. Its description is a migration recipe (dependency bump, component themes, `Modifier.styleable`) — adopt deliberately, not mid-feature. Triaged on frontmatter only; read the body at adoption time. |

### Evaluate separately (its own decision, not this PRD)

`devtools/android-cli` — the `android` CLI manages AVDs, runs apps, inspects UI: it overlaps
the e2e skills' hand-rolled emulator workflows **and** the hard-won Windows workarounds
(adb port zombies, VPN squatting 5555, Start-Process launches) captured in session memory.
Swapping that layer is a real project with real risk. Open question below.

### Installed but guarded or dormant

| Skill                                                                              | Reason                                                                                                                                                                                                                                                                                                       |
| ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `navigation/navigation-3`                                                          | **Guarded — installed per the all-21 decision, but its advice must not be followed here.** OpenLoop deliberately uses a sealed-state machine + exhaustive `OpenLoopNavHost` `when` (Lesson 014, `PRD-mission-control.md`), not Navigation. The `CLAUDE.md` precedence section exists chiefly for this skill. |
| `jetpack-compose/migration/migrate-xml-views-to-jetpack-compose`                   | Pure Compose app; nothing to migrate.                                                                                                                                                                                                                                                                        |
| `build-system/agp/agp-9-upgrade`                                                   | Already on AGP 9.3.1. (Also: AGP ceiling is set by the installed Studio, not Maven.)                                                                                                                                                                                                                         |
| `media/media3-cast-integration`                                                    | Cast is out of scope. Revisit only if a "play loops on TV" feature is ever proposed — YAGNI today.                                                                                                                                                                                                           |
| `play/play-billing-library-version-upgrade`                                        | Free app, no IAP.                                                                                                                                                                                                                                                                                            |
| `play/engage-sdk-integration`, `identity/verified-email`, `device-ai/appfunctions` | No matching surface in the app.                                                                                                                                                                                                                                                                              |
| `tv/*`, `wear/*`, `xr/*`                                                           | Form factors out of scope.                                                                                                                                                                                                                                                                                   |

## How future features use these skills

The half of #139 that outlives this PR — what each adopted skill does for work we haven't
started:

| Future work                                           | Skill leverage                                                                                                                                                                                |
| ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Manual camera controls (exposure/focus for loops)     | camerax `Camera2Interop` structural blueprint: ViewModel state → controller → session wiring, pre-decomposed to avoid the timeout failure mode it documents.                                  |
| Low-light / night loops                               | camerax `low-light.md` (Night Mode, LLB).                                                                                                                                                     |
| Fold posture-aware capture UI                         | camerax `foldables.md` (hinge states) now; `jetpack-compose/adaptive` when layouts split.                                                                                                     |
| Long-recording stability                              | camerax `thermals.md` (`StreamUseCase`, thermal throttling) — pairs with the OEM regression lanes.                                                                                            |
| Dual-lens photo booth / PiP capture                   | camerax `ConcurrentCamera` guidance.                                                                                                                                                          |
| Every release                                         | r8-analyzer report on keep-rule drift + size regression, alongside the existing DoD gate; play-policy-insights (if the pilot lands) as the pre-upload compliance check next to `pr-reviewer`. |
| New lens features touching `FaceTracker`/`LensAnchor` | camerax `mlkit-spatial.md` (rotation, mirroring, coordinate mapping).                                                                                                                         |
| `CameraManager` test coverage                         | camerax `testing.md` (`FakeCameraConfig`, async lifecycle validation).                                                                                                                        |

## Implementation plan (approved, sized S)

1. Branch `feature/android-skills`. ✅
2. Enable the plugin at **project** scope (snippet in Decision). Owner, 2026-08-22: (a) removed
   `"android-skills@android-skills": true` from `enabledPlugins` in `~/.claude/settings.json`
   (the 2026-08-21 install had landed at user scope) ✅; (b) `claude plugin install
   android-skills@android-skills --scope project` from the repo root ✅ — note it writes only
   `enabledPlugins`, so the `extraKnownMarketplaces` entry was added to `.claude/settings.json`
   by hand to make the checked-in file self-contained on a fresh clone; (c) restart ✅ 2026-08-22 —
   a fresh session in the repo listed all 21 `android-skills:<name>` skills while
   `grep -n android-skills ~/.claude/settings.json` hit only line 39, under `extraKnownMarketplaces`
   (the user-level `enabledPlugins` map has no android-skills entry); (d) verify from another
   folder that the `android-skills:*` entries are gone ✅ 2026-08-22 — from a non-repo folder
   (`%LOCALAPPDATA%\Temp\claude\…\scratchpad`; `git rev-parse` → "not a git repository"),
   `claude -p "Reply with only the count of skills in your list whose name starts with
   android-skills:"` answered `0`.
3. Add the "Google Android Skills — Precedence" section to `CLAUDE.md`. ✅
4. Add new terms to `cspell.json` if the gate flags them. ✅ 2026-08-22 — local `cspell` on the
   PR files flagged six (`appfunctions`, `frontmatter`, `keepradius`, `mlkit`, `PYTHONUTF`,
   `uncurated`); CI stayed green only because the Tier 3 Markdown job is soft (`|| true`).
5. Run the play-policy-insights pilot; record verdict in this PRD (Open Questions → resolved).
6. Validation after restart: confirm skills appear in the session skill list ✅ (all 21 listed as
   `android-skills:<name>`, session of 2026-08-22) and `camerax` triggers on camera-domain work
   ☐ (pending the first camera-domain task).
7. Close #139 with a pointer here.

## Constraints

- Apache 2.0 both sides — compatible; the plugin installs outside the repo tree, so no
  license files enter this codebase.
- Upstream accepts no public contributions — feedback goes via their GitHub issues.
- Skill bodies verified this session: camerax, r8-analyzer. The pilot/later tiers were
  triaged on frontmatter + file inventory; each gets its body read at adoption time.

## Open questions

1. **`android` CLI**: adopt as a tool (and eventually refactor e2e emulator handling onto it),
   or stay with the proven hand-rolled workflows? Leaning: stay, revisit when the CLI matures on Windows.
2. **play-policy-insights pilot**: ~~does its report add anything over the existing
   `docs/play-store/` data-safety docs + `pr-reviewer` gate?~~ **Resolved 2026-08-21 — yes,
   keep it.** The pilot (8 worker agents + critic pass over the full app) surfaced findings
   the existing docs missed, each with line-verified evidence: (1) the `source_label`
   Crashlytics key can carry a user-picked file name on the legacy import fallback,
   contradicting the drafted Data Safety form's "Files — NOT collected"; (2) & (3) Firebase
   Analytics/Crashlytics upload with no in-app disclosure while onboarding claims "100% on
   your phone"; (4) the Play Console FGS declaration is needed for both `mediaProcessing`
   and `dataSync`. Verdict: run it before each Play submission with material telemetry,
   permission, or FGS changes. One Windows note: run its scripts with `PYTHONUTF8=1` (the
   report writer emits emoji that cp1252 can't encode).
3. **Update cadence**: is "refresh when working in the domain" enough, or do we want a
   quarterly upstream diff? Mechanism: third-party marketplaces don't auto-update by default —
   refresh is a manual `/plugin marketplace update android-skills` + restart; bump the `Upstream`
   commit in this header when you do, and re-check the precedence collisions against the new bodies.
