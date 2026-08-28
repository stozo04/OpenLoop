# Documentation layout — OpenLoop

**Single rule:** all project documentation lives under `docs/`. The only exceptions at the repo
root are `README.md` and `CLAUDE.md` (convention).

**Documented exception — `swarm/`** (owner, 2026-08-15). The two-agent lens-build harness is a
*working tool*, not documentation: its `GOAL.md` / `SWARM-PROMPT.md` are pasted into agent sessions
and its `tools/` render a live message bus. It stays out of `docs/` for two reasons — `docs/` is
served publicly by GitHub Pages, and the harness must sit beside the code it drives. Its durable
output lands here as `PRD-camera-lenses.md` §13. See [`../swarm/README.md`](../swarm/README.md).

**Private docs:** owner-only notes (keystore paths, personal checklists) go in `docs/local/`.
That folder is **gitignored** — never commit secrets there.

**No archive folders.** Shipped work is captured in git history, `docs/lessons_learned/`, and
`docs/guides/`. In-flight features use GitHub issues/PRs — not `docs/active/`, `docs/completed/`,
or `docs/diagnostics/`.

---

## Folder map

| Path                                               | What belongs here                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | Tracked in git?     |
| -------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------- |
| [`PRD-mission-control.md`](PRD-mission-control.md) | Durable design record: design tokens, storage layout, decision log (the architecture snapshot lives in `CLAUDE.md`)                                                                                                                                                                                                                                                                                                                                                                                                                | Yes                 |
| `PRD-<feature>.md`                                 | Per-feature PRDs, signed off before code ([capture-zoom](PRD-capture-zoom.md), [crashlytics-autotriage](PRD-crashlytics-autotriage.md), [camera-lenses](PRD-camera-lenses.md), [photo-capture](PRD-photo-capture.md), [aso-discoverability](PRD-aso-discoverability.md), [speed-curves](PRD-speed-curves.md), [photo-booth](PRD-photo-booth.md), [android-skills](PRD-android-skills.md), [multi-face-lenses](PRD-multi-face-lenses.md), [lens-interactions](PRD-lens-interactions.md), [lens-hand-flick](PRD-lens-hand-flick.md)) | Yes                 |
| [`ANDROID_STANDARDS.md`](ANDROID_STANDARDS.md)     | **OpenLoop-specific** Android rules + the Google links that justify them. Generic Google guidance is **not** mirrored here — it lives in the `android/skills` plugin ([`guides/android-skills.md`](guides/android-skills.md)) or on `developer.android.com` (layering rule, [#143](https://github.com/stozo04/OpenLoop/issues/143))                                                                                                                                                                                                | Yes                 |
| [`DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md)   | Ready-for-PR verification gate                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | Yes                 |
| [`STATIC_ANALYSIS.md`](STATIC_ANALYSIS.md)         | Lint + Inspect Code merge policy                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | Yes                 |
| [`TEST_COVERAGE.md`](TEST_COVERAGE.md)             | Testing pyramid, inventory, frameworks                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | Yes                 |
| [`FIREBASE.md`](FIREBASE.md)                       | Crashlytics auto-triage **runbook** (token renewal, function deploy, break-glass). Stays at the root — `.github/workflows/crashlytics-autotriage.yml` and `PRD-crashlytics-autotriage.md` link to this path                                                                                                                                                                                                                                                                                                                        | Yes                 |
| [`guides/`](guides/README.md)                      | Durable reference: reverse algorithm, Robolectric boundaries, OEM/RTL lanes, localization, lens-art asset workflow, the `android/skills` plugin                                                                                                                                                                                                                                                                                                                                                                                    | Yes                 |
| [`play-store/`](play-store/README.md)              | Play Console paste text + **store upload graphics**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | Yes                 |
| [`lessons_learned/`](lessons_learned/README.md)    | Distilled rules from past PR reviews — **core tier read every session**                                                                                                                                                                                                                                                                                                                                                                                                                                                            | Yes                 |
| `e2e/`                                             | Agent/human E2E run reports + proof screenshots (timestamped `.md` + PNG) — **see retention rule below**                                                                                                                                                                                                                                                                                                                                                                                                                           | Yes                 |
| `local/`                                           | **Private** owner notes (signing playbook, personal paths) — **never commit**                                                                                                                                                                                                                                                                                                                                                                                                                                                      | **No** (gitignored) |
| [`privacy-policy.html`](privacy-policy.html)       | GitHub Pages host for Play privacy URL                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | Yes                 |
| [`index.html`](index.html)                         | **The public landing page** at `https://stozo04.github.io/OpenLoop/` — brand token, meta description, `SoftwareApplication` JSON-LD, Play + GitHub links. **Not a doc**: edit it as a shipped web page, and keep its links in step with the live listing                                                                                                                                                                                                                                                                           | Yes                 |

> ⚠️ **Everything in `docs/` is publicly served.** Pages publishes from `main` → `/docs`, so every
> `.md` here is world-readable and indexable, not just the two `.html` files. Moving publishing to a
> separate folder would break the `privacy-policy.html` URL that Play Console points at, so the rule
> is simply: don't put anything in `docs/` you wouldn't publish (`docs/local/` is gitignored for that).

**Do not create:** `docs/active/`, `docs/completed/`, `docs/diagnostics/`, `docs/android-16/`,
`docs/prompts/`, or loose `.md` files outside the folders above (except the six root-level
files in the map: `ANDROID_STANDARDS.md`, `DEFINITION_OF_DONE.md`, `STATIC_ANALYSIS.md`, `TEST_COVERAGE.md`,
`FIREBASE.md`, and `PRD-mission-control.md` — plus the `PRD-<feature>.md` set).

**E2E proof retention:** `docs/e2e/` grows one report + screenshots per verified PR and is already
the second-largest folder in the repo (screenshots run ~1.4 MB each). **Keep the newest proof per
feature area; delete older reports and their PNGs once the PR is merged** — the PR itself keeps the
evidence in its description and history. When a PR supersedes an earlier proof for the same feature,
delete the old one in that PR rather than accumulating both.

**Android version policy:** web-search [Google's behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16) and read `ANDROID_STANDARDS.md` §11 — do not maintain a local Android-16 mirror.

**Crashlytics / codec issues:** `ReverseCrashlytics.kt`, `DeviceMediaHints.kt`, and lessons 020 / 023 — not a separate diagnostics folder.

---

## Image & asset layout

| Path                                       | What belongs here                                                                                                       | Do not use for                       |
| ------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| `app/src/main/res/`                        | In-app drawables, mipmaps, raw — **only** what ships in the APK                                                         | Play Store uploads, docs screenshots |
| [`docs/play-store/`](play-store/README.md) | **Play Console graphics** (`play_store_icon_512.png` icon, `main-image.png` feature graphic, `store-*.png` screenshots) | In-app launcher icons                |
| `docs/e2e/`                                | E2E proof screenshots tied to a run report                                                                              | Marketing, store listing             |

In-app launcher assets live only under `app/src/main/res/` (see root [`README.md` → Brand Assets](../README.md#brand-assets)).

---

## Enforcement

1. **Agents:** `CLAUDE.md` mandates reading the core `docs/lessons_learned/` tier and this layout before adding docs.
2. **PR review:** the [`pr-reviewer`](../.claude/skills/pr-reviewer/SKILL.md) skill flags new `.md` outside `docs/` (except root `README.md` / `CLAUDE.md`).
3. **CI — doc layout gate:** [`.github/workflows/doc-layout.yml`](../.github/workflows/doc-layout.yml) fails PRs that **add** new `*.md` outside allowed paths. Allowed today: `docs/`, root `README.md` / `CLAUDE.md`, `swarm/`, `.claude/`, `.cursor/` (Cursor `SKILL.md` packages, same class as `.claude/skills/`), and `twisted-tounge/*.md` — the last because a reverse-engineering guide has to sit with the third-party reference project it documents, whose vendor assets are gitignored (owner instruction, 2026-08-16).
4. **CI / Tier 3 static analysis:** [`STATIC_ANALYSIS.md`](STATIC_ANALYSIS.md) — markdownlint, table alignment, link check, cspell and JSON validity over the whole tracked tree, hard. Locally the same checks are gates 6–8 of `scripts/pre-pr-sweep.ps1` (tooling lives in `scripts/`, not here — it is not documentation).
5. **Secrets:** `keystore.properties`, `*.jks`, and `docs/local/` are gitignored.

---

## Quick links

- Play Store submission pack: [`play-store/README.md`](play-store/README.md)
- Testing guides index: [`guides/README.md`](guides/README.md)
- Reverse algorithm reference: [`guides/reverse-video-research.md`](guides/reverse-video-research.md)
- Porting a third-party AR effect (DeepAR → a native lens): [`twisted-tounge/GUIDE.md`](../twisted-tounge/GUIDE.md)
