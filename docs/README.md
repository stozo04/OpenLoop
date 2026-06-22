# Documentation layout — OpenLoop

**Single rule:** all project documentation lives under `docs/`. The only exceptions at the repo
root are `README.md` and `CLAUDE.md` (convention).

**Private docs:** owner-only notes (keystore paths, personal checklists) go in `docs/local/`.
That folder is **gitignored** — never commit secrets there.

**No archive folders.** Shipped work is captured in git history, `docs/lessons_learned/`, and
`docs/guides/`. In-flight features use GitHub issues/PRs — not `docs/active/`, `docs/completed/`,
or `docs/diagnostics/`.

---

## Folder map

| Path | What belongs here | Tracked in git? |
|------|-------------------|-----------------|
| [`PRD-mission-control.md`](PRD-mission-control.md) | Architecture, components, design tokens — read before structural changes | Yes |
| [`ANDROID_STANDARDS.md`](ANDROID_STANDARDS.md) | Google Android practices (linked specs) | Yes |
| [`DEFINITION_OF_DONE.md`](DEFINITION_OF_DONE.md) | Ready-for-PR verification gate | Yes |
| [`STATIC_ANALYSIS.md`](STATIC_ANALYSIS.md) | Lint + Inspect Code merge policy | Yes |
| [`TEST_COVERAGE.md`](TEST_COVERAGE.md) | Testing pyramid, inventory, frameworks | Yes |
| [`guides/`](guides/README.md) | Plain-English how-tos + durable reference (reverse algorithm, Robolectric, OEM lanes) | Yes |
| [`play-store/`](play-store/README.md) | Play Console paste text + **store upload graphics** | Yes |
| [`lessons_learned/`](lessons_learned/README.md) | Distilled rules from past PR reviews — **read every session** | Yes |
| [`e2e/`](e2e/) | Agent/human E2E run reports + proof screenshots (timestamped `.md` + PNG) | Yes |
| [`local/`](local/) | **Private** owner notes (signing playbook, personal paths) — **never commit** | **No** (gitignored) |
| [`privacy-policy.html`](privacy-policy.html) | GitHub Pages host for Play privacy URL | Yes |

**Do not create:** `docs/active/`, `docs/completed/`, `docs/diagnostics/`, `docs/android-16/`,
`docs/prompts/`, or loose `.md` files outside the folders above (except the five root-level
standards files).

**Android version policy:** web-search [Google's behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16) and read `ANDROID_STANDARDS.md` §11 — do not maintain a local Android-16 mirror.

**Crashlytics / codec issues:** `ReverseCrashlytics.kt`, `DeviceMediaHints.kt`, and lessons 020 / 023 — not a separate diagnostics folder.

---

## Image & asset layout

| Path | What belongs here | Do not use for |
|------|-------------------|----------------|
| `app/src/main/res/` | In-app drawables, mipmaps, raw — **only** what ships in the APK | Play Store uploads, docs screenshots |
| [`docs/play-store/`](play-store/) | **Play Console graphics** (`play_store_icon_512.png`, `feature_graphic_1024x500.png`) | In-app launcher icons |
| [`docs/e2e/`](e2e/) | E2E proof screenshots tied to a run report | Marketing, store listing |

In-app launcher assets live only under `app/src/main/res/` (see root [`README.md` → Brand Assets](../README.md#brand-assets)).

---

## Enforcement

1. **Agents:** `CLAUDE.md` mandates reading `docs/lessons_learned/` and this layout before adding docs.
2. **PR review:** the [`pr-reviewer`](../.claude/skills/pr-reviewer/SKILL.md) skill flags new `.md` outside `docs/` (except root `README.md` / `CLAUDE.md`).
3. **CI — doc layout gate:** [`.github/workflows/doc-layout.yml`](../.github/workflows/doc-layout.yml) fails PRs that **add** new `*.md` outside allowed paths.
4. **CI / Tier 3 static analysis:** [`STATIC_ANALYSIS.md`](STATIC_ANALYSIS.md) — `markdown-link-check` on changed Markdown.
5. **Secrets:** `keystore.properties`, `*.jks`, and `docs/local/` are gitignored.

---

## Quick links

- Play Store submission pack: [`play-store/README.md`](play-store/README.md)
- Play Store beginner walkthrough (private): `docs/local/play-store-beginner-guide.md` on your machine
- Testing guides index: [`guides/README.md`](guides/README.md)
- Reverse algorithm reference: [`guides/reverse-video-research.md`](guides/reverse-video-research.md)
