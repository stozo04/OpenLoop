# The protocol — read, don't paste

Nobody pastes this file. Claude Code loads it via `/lens-swarm`; Codex and Kayley get a few-line
kickoff that tells them to read it (see [`README.md`](README.md)). This is the file to **edit** when
the rules change — both agents re-read it from disk.

---

You are building AR camera lenses alongside **another live agent from a different vendor**. Not a
subagent — a peer with its own session, its own tools, and its own opinions. If you are Claude Code
your counterpart is OpenAI Codex; if you are Codex your counterpart is Claude Code. **Neither of you
is in charge.**

**Read `swarm/GOAL.md` first.** That is the brief. It is the authority.

## Who decides what

- **Steven Gates** is the owner and judge, and he is **unavailable for this run**. His standing
  rulings are already in `swarm/collab/decisions.md` — read them, they are binding, and there is
  nobody to ask for more. He has given the build sign-off in advance: *once you two agree on the
  three lenses, you start building.* Do not wait for a second approval.
- **Kayley is the acting judge in his absence.** She is **Grok**, in her own session, and **reads the
  bus directly** — posting a question addressed to her is all it takes. Her ruling is binding. Use
  her on a real deadlock, not for reassurance.
- **If she goes quiet, you still do not stall.** GOAL.md §3 step 6 gives you a ladder that always
  terminates — starting with the ballot Steven and Kayley pre-seeded in `decisions.md`. **Use it and
  record which rung you took.** Stalling is the one failure mode nobody can rescue you from.
- **You two** decide the three undecided lenses, and every implementation call inside GOAL.md's
  constraints.

**Git: hands off.** Steven's standing ruling (2026-08-15, in `decisions.md`): **no commits, no push,
no PR.** No new branch, no deleting his files, and never `checkout` / `stash` / `reset` / `clean` —
your counterpart's uncommitted work is in this tree. Read-only git is always fine. Kayley's authority
covers ties, kills, and locking the shortlist; it does not cover git.

## Workspace

```text
C:/Users/gates/Personal/OpenLoop
```

You are already on branch `feature/AR-Lenses`. Stay on it.

The harness lives in `swarm/`. The app lives in `app/`. Before anything else, verify these exist:

- `swarm/collab/bus/claude.jsonl` — **Claude is the only writer**
- `swarm/collab/bus/codex.jsonl` — **Codex is the only writer**
- `swarm/collab/bus/kayley.jsonl`, `swarm/collab/bus/steven.jsonl` — humans
- `swarm/collab/decisions.md`
- `swarm/collab/LESSONS.md`

Missing? Create it once, then post a status.

## Required reading before you touch code

Non-negotiable, in this order — `docs/OPENLOOP_INSTRUCTIONS.md` mandates it and the lens work sits on top of it:

1. `docs/OPENLOOP_INSTRUCTIONS.md` (the root `CLAUDE.md` / `AGENTS.md` point here)
2. `docs/lessons_learned/README.md`, then **every core lesson in full: 008 and 011–032**.
   Skim the index rows for 001–007, 009, 010.
   **031** (attach the effect once, switch by uniform) and **032** (the tracker's frame is not the
   renderer's frame) are the two that will bite you directly.
3. `docs/PRD-camera-lenses.md` — the whole thing. §4b (prop vs character), §5 (architecture),
   §11.1 (manual QA) are load-bearing.
4. `app/src/main/java/io/github/stozo04/openloop/camera/lens/Lens.kt` — the catalogue and its KDoc
   face-unit table. **`Lens.Broccoli` is the golden reference** (Steven: *"a perfect working
   copy"*). Every new character lens copies its shape, its asset pipeline, and its geometry
   discipline. Diverging from it needs a stated reason.

Post a `status` on the bus when you have finished reading, so your counterpart knows you are live.

## How you talk

`swarm/collab/bus/claude.jsonl` and `swarm/collab/bus/codex.jsonl`.

**You append only to your own file. Never write to the other agent's.** One writer per file is what
makes concurrent appends safe without locks.

Prefer absolute paths:
`C:/Users/gates/Personal/OpenLoop/swarm/collab/bus/<you>.jsonl`

One JSON object per line:

```text
{"id":"<you>-<epoch_ms>","from":"claude|codex","to":"...","replyTo":"<id|null>","type":"proposal|question|answer|ack|status|correction|claim|release|done","createdAt":"<iso>","body":{...}}
```

Rules — each of these prevented a real failure in a prior run:

1. **Re-read the other agent's file immediately before you append.**
2. **IDs are `<agent>-<epoch_ms>`.** Never a shared counter.
3. **Never go silent.** End every message with `WORKING ON: x` or `WAITING FOR: y`.
4. **Durable state.** Research and decisions go in files; coordination goes on the bus.
5. **Disagree with evidence** (URL + date + quote/number), not vibes. Wrong? Retract loudly.
6. **Announce long work BEFORE disappearing** — a web-research sweep, a Gradle run, an emulator boot
   — and check in while it runs.

Optional helper (writes the canonical bus regardless of cwd):

```text
node C:/Users/gates/Personal/OpenLoop/swarm/tools/bus-post.mjs <claude|codex> <payload.json>
```

## Sharing one working tree — the part that will bite you

You are both editing **the same checkout**. There is no merge step to save you.

**Claim before you edit a shared file.** Post `type: "claim"` with `{"path": "..."}`, do the edit,
post `type: "release"`. Do not touch a claimed path. Shared paths that always need a claim:

- `app/src/main/java/io/github/stozo04/openloop/camera/lens/Lens.kt` — the catalogue every lens lands in
- `app/src/main/java/io/github/stozo04/openloop/camera/lens/LensAnchor.kt` and `LensSurfaceProcessor.kt`
- any test file, `swarm/collab/decisions.md`, `swarm/collab/LESSONS.md`, `docs/PRD-camera-lenses.md`

Claims are short. If you are still holding one after ~10 minutes, post a status saying why.

**Own your lenses.** Split the four so each agent owns art, geometry, and tuning for specific
lenses end to end. Agree the split on the bus before building. Your own art files, your own
scratch scripts — no claim needed.

**One Gradle lane.** Two Gradle runs in one tree fight over the daemon, the build cache, and
`app/build/`. Claim `gradle` the same way you claim a file, run, post the result, release. Same for
the emulator and `adb` — one driver at a time.

**Never `git checkout`, `git stash`, `git reset`, or `git clean`.** Your counterpart's uncommitted
work is in that tree. Read-only git (`status`, `diff`, `log`) is always fine.

## Deadlock

You are deadlocked when **two full rounds** have passed, both sides have posted evidence, and there
is still no ACK. Not before.

Then: post a `question` with `"to": "kayley"` stating the two positions in ≤5 lines each and the
exact thing being decided. She reads the bus, so posting is enough — and it renders **UNANSWERED** in
Steven's viewer either way. Stop arguing that item and work on something else while you wait. Her
answer is binding and goes into `decisions.md` verbatim.

**If no answer comes back, resolve it yourself.** Do not park the build waiting on someone who may
not be watching. Apply GOAL.md §3 step 6 — pre-seeded ballot, then smaller scope, then stronger
evidence, then alphabetical — and write down which rung you used and why.

## Human follow-along

Steven watches `swarm/bus-follow.html`, generated by:

```text
node swarm/tools/render-bus.mjs --watch
```

You do **not** maintain that HTML. Post clean bus messages and keep `decisions.md` current. Give
every message a short `headline`, an `oneSentence`, and a `WORKING ON` — **inside `body`**, which is
the contract. (The viewer also accepts them at the top level, because Kayley posted three that way
before the shape was pinned down, but write them into `body`.) That page is Steven's only window
into what you are doing; a message with no headline is an invisible message.

## The work

### Phase 1 — Discovery (joint, do not split)

1. Read GOAL.md and the required reading. Post a status.
2. **Independently** web-search trending / most-loved lenses. Do not peek at the other's shortlist.
   Write your trail to `swarm/collab/research-<you>.md`.
3. Post **4–6 candidates each** in GOAL.md §2.4's shape — including the **tier**, the **face-unit
   geometry**, and **≥3 dated sources**.
4. Cross-read. Argue with evidence. Kill anything failing GOAL.md §2.1. Merge the strong halves.
5. Converge on **exactly three** in `swarm/collab/decisions.md`. Both ACK explicitly.
6. Post a `status` naming the final four (Football + your three) so Steven sees it before code moves.

Minimum two rounds. A third only if it would actually change the list.

> If your session has **no web search**, say so on the bus immediately. Do not fake evidence and do
> not quietly skip the research. The roles shift: the searching agent gathers, the other one
> adversarially verifies every claim and argues the shortlist. Symmetric research failing silently is
> worse than an asymmetric split you both agreed to.

### Phase 2 — Build

Agreement is the trigger. No further sign-off needed.

1. Agree the lens-ownership split on the bus.
2. **Football first**, by both of you if useful — it is the pre-decided one, and it proves the
   character path end to end (GOAL.md §1).
3. Art pipeline per lens: key background to alpha → autocrop → downscale → `cwebp -q 90` into
   `app/src/main/res/drawable-nodpi/`, plus a carousel thumbnail. Alpha stays lossless in WebP, so
   the cut-out edge is bit-exact — that edge is what makes a character read.
4. Geometry from GOAL.md §4.1's face-unit table. **Measure, do not guess.**
5. Run the inherited catalogue-driven tests after every entry — they cover new lenses automatically
   and will fail on a badly-shaped one.
6. Clear the full Definition of Done gate (GOAL.md §6) before anyone says "done".
7. Fold the shortlist into `docs/PRD-camera-lenses.md` as §13.
8. Write the manual-QA checklist for Steven, naming what you could not verify.

### Honesty rules for Phase 2

- **You cannot see a face on the emulator.** The virtual scene has none. Verify the bind, the render
  path, the recording finalize, and the math — then say plainly that face-relative placement is
  unverified until Steven runs it on hardware. Never imply you saw a lens land on a face.
- **A green build is not a working lens.** Report what you ran and what it actually printed.
- **Fix red, do not route around it.** Any failure you hit — including one you did not cause — gets
  fixed before a PR. Production zero-error rule. **There is no escalation hatch this run**: Steven is
  away, so "escalate and stop" is not an available move. Fix it, or record precisely why it cannot be
  fixed and keep every other part of the work green.

## Done means (for you)

- Four lenses in `Lens.kt`, each rendering in preview, in a saved video, and in a saved photo
- Definition of Done gate genuinely green (build ×2, unit, instrumented, lint, run + screenshot)
- `decisions.md` locked with mutual ACKs and every judge ruling recorded
- `LESSONS.md` has honest notes, including the things that wasted time
- A manual-QA checklist for Steven, with the unverifiable items named as unverifiable
- The work sitting **uncommitted** in the tree on `feature/AR-Lenses` — the diff is the deliverable
- **`WAITING FOR: Steven — commit/push/PR go`.** You do not commit, push, or open the PR.

Begin by reading `swarm/GOAL.md`, doing the required reading, verifying the bus, and posting your
opening status.
