# Google Android Skills — using the `android/skills` plugin

Google ships 21 agent skills for Android development
([`android/skills`](https://github.com/android/skills), Apache 2.0). OpenLoop installs all of them
through the official Claude Code plugin. The *what* and *why* — full triage, collision points,
pilot verdicts — is [`../PRD-android-skills.md`](../PRD-android-skills.md). This page is the *how*.

**Precedence first:** where a Google skill disagrees with this repo's documented decisions, the
repo wins — `OPENLOOP_INSTRUCTIONS.md` → "Google Android Skills — Precedence" lists the known collisions
(`navigation-3`, `camerax`'s `MlKitAnalyzer` preference, the AGP ceiling).

## How Claude knows the skills exist

Nothing to wire. Every turn, Claude Code injects the **name + description** of every enabled skill
into the model's context; when a task matches a description, Claude invokes that skill itself. The
description *is* the trigger — `camerax`'s reads "Use when implementing camera features, handling
asynchronous recording lifecycles …". The 21 descriptions cost ~2 k tokens per turn, which is why
the plugin is scoped to this project only (below).

To see what is loaded: `/plugin` → **Installed**, or ask the session to list its available skills.
The plugin's skills appear as `android-skills:<name>`.

## Forcing a specific skill

Auto-triggering is probabilistic. When you want a skill for sure, name it:

| You want                                           | Type                                                               |
| -------------------------------------------------- | ------------------------------------------------------------------ |
| CameraX guidance on a camera change                | `/android-skills:camerax` (or "use the camerax skill for this")    |
| A keep-rule / app-size report before a release     | `/android-skills:r8-analyzer`                                      |
| A Play-policy compliance audit before a submission | `/android-skills:play-policy-insights`                             |
| Anything else                                      | `/android-skills:<skill-name>` — names in the PRD's Triage section |

## The two flows worth running on a cadence

**r8-analyzer — every release.** Runs `./gradlew :app:analyzeReleaseR8Config`, then its Python
analysis; the scored keep-rule report lands in `tmp/keepradius/analysis_result.txt` (gitignored).
Review its broad / redundant-rule items against `app/proguard-rules.pro`.

**play-policy-insights — before a Play submission that changes telemetry, permissions, or
foreground services.** A multi-agent Python harness; it writes its workspace to
`.scratch/play_policy_insights_<uuid>/` (gitignored) and produces a compliance report. On Windows,
run it with `PYTHONUTF8=1` set — the report writer emits emoji that cp1252 can't encode. The
2026-08-21 pilot findings are recorded in the PRD (Open question 2).

## Scope: this project only

The plugin belongs at **project** scope (`.claude/settings.json`, checked in), never user scope —
user scope loads the 21 descriptions into every project on the machine, and the precedence guard
in this repo's `OPENLOOP_INSTRUCTIONS.md` protects none of them.

**Fresh clone / new machine — the skills do not load by themselves.** The checked-in
`extraKnownMarketplaces` entry makes the marketplace name resolve without a `/plugin marketplace add`,
but since Claude Code v2.1.195 a plugin that only the project's `.claude/settings.json` enables, and
that comes from an external source such as a GitHub repository, **does not load until you install it
once** — until then Claude Code reports it as not installed and prints the installation command
([Configure team marketplaces](https://code.claude.com/docs/en/discover-plugins#configure-team-marketplaces),
read 2026-08-22). Run it with the scope flag — **`claude plugin install android-skills@android-skills
--scope project`** — because without `--scope project` the CLI installs to **user** scope, which is
exactly the drift the migration below undoes. Then restart (or `/reload-plugins`) and confirm the
`android-skills:*` entries appear in this repo and *only* this repo.

One-time migration if it was installed at user scope:

1. Remove `"android-skills@android-skills": true` from `enabledPlugins` in
   `~/.claude/settings.json` (the `extraKnownMarketplaces` entry can stay — it only names a source).
2. From the repo root: `claude plugin install android-skills@android-skills --scope project` —
   this writes `enabledPlugins` into `.claude/settings.json` but **not** the marketplace source;
   add the `extraKnownMarketplaces` block from the PRD's Decision snippet by hand so the file
   resolves on a fresh clone, then commit it.
3. Restart Claude Code. The first launch shows the folder-trust prompt once (a project-level
   `extraKnownMarketplaces` applies only after the folder is trusted).
4. Verify: open Claude Code in any other folder — the `android-skills:*` entries must be gone.

Settings precedence is managed → project-local → project → user, so a project file can also
*disable* a user-scoped plugin for one repo (`"android-skills@android-skills": false`).

## Updating

Third-party marketplaces don't auto-update by default. `/plugin marketplace update android-skills`,
then restart; record the new upstream commit in the PRD header and re-check the precedence
collisions against the new skill bodies — a collision list is only as current as the skills it was
written against.
