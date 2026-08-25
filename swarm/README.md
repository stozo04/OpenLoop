# AR Lens Swarm — Claude Code × Codex

Two agents from different vendors build four AR lenses for OpenLoop in one shared checkout.
**Judge: Steven (AFK this run). Acting judge: Kayley — Grok, in her own session, reads the bus.**

## Quick start

**Do not paste the long documents.** They are files so the agents read them themselves — you hand
each agent a few lines that say who it is and what to read.

1. Open the sessions **in this repo root** (`C:/Users/gates/Personal/OpenLoop`).
2. **Claude Code:** type `/lens-swarm`.
3. **Codex:** paste this:
   > You are **codex** in a three-way AR lens swarm in this repo. Your counterpart is Claude Code —
   > a peer, not a subagent. Kayley (Grok) is the acting judge; Steven is AFK and his standing
   > rulings are binding. Read in full, in order: `swarm/SWARM-PROMPT.md`, `swarm/GOAL.md`,
   > `swarm/collab/decisions.md`, then everything SWARM-PROMPT lists under Required reading. Your
   > bus file is `swarm/collab/bus/codex.jsonl` and you are its only writer — put `headline`,
   > `oneSentence` and `WORKING ON` inside `body`. Post your opening status, then start Phase 1.
   > Steven's sign-off is already on record; do not wait for permission.
4. **Kayley:** paste the block inside [`KAYLEY-PROMPT.md`](KAYLEY-PROMPT.md).
5. In a spare terminal:

   ```text
   node swarm/tools/render-bus.mjs --watch
   ```

6. Open `swarm/bus-follow.html` in a browser. It self-refreshes every 2s.

`SWARM-PROMPT.md` is still the full protocol — the agents read it from disk in step 2/3, and it is
the file to edit when the rules change. You just never have to paste it.

## Layout

| Path                   | What                                                                          |
| ---------------------- | ----------------------------------------------------------------------------- |
| `GOAL.md`              | The brief — what to build, the quality bar, the Definition of Done            |
| `SWARM-PROMPT.md`      | Paste into both agents                                                        |
| `KAYLEY-PROMPT.md`     | Paste into Grok when a tie-break is needed, plus how to relay the answer back |
| `collab/bus/*.jsonl`   | The message bus — **one writer per file**                                     |
| `collab/decisions.md`  | The locked shortlist, ACKs, and judge rulings (the PRD of record)             |
| `collab/LESSONS.md`    | Honest notes on what worked and what wasted time                              |
| `collab/research-*.md` | Each agent's evidence trail                                                   |
| `tools/render-bus.mjs` | Renders the bus + decisions into `bus-follow.html`                            |
| `tools/bus-post.mjs`   | Safe bus append helper                                                        |
| `bus-follow.html`      | Generated. Gitignored.                                                        |

## Breaking a tie (Kayley = Grok)

Kayley reads the bus herself and writes `collab/bus/kayley.jsonl` — a blocked agent just posts a
question addressed to her, and it renders **UNANSWERED** in the viewer until she rules.

**If she goes quiet the agents don't stall** — `GOAL.md` §3 step 6 gives them a ladder that always
terminates, starting with the ballot already sitting in `collab/decisions.md`. `KAYLEY-PROMPT.md`
carries a manual copy-paste fallback for the case where she loses file access.

## Posting as a human

To weigh in — or to post a ruling by hand — write a payload and post it:

```json
{ "to": "both", "type": "answer", "replyTo": "claude-1755300000000",
  "body": { "headline": "Tie-break: go with X", "why": "..." } }
```

```text
node swarm/tools/bus-post.mjs kayley payload.json
```

Judge and tie-break messages render highlighted in the viewer.

## Gitignored

`collab/bus/*.jsonl` and `bus-follow.html` — message churn never lands in a commit. The agents'
durable output (`decisions.md`, `LESSONS.md`, `research-*.md`) is tracked.
