#!/usr/bin/env node
// Claude Code PreToolUse hook: no PR gets created without a green pre-PR sweep for the current HEAD.
//
// Wired in .claude/settings.json for the Bash tool (matches a `gh pr create` invocation) and the
// GitHub MCP `create_pull_request` tool. Reads the hook JSON from stdin; exit 2 blocks the call and
// feeds the reason back to the agent. Any other tool call passes straight through.
//
// The receipt is written by scripts/pre-pr-sweep.ps1 only when every gate is green, and it carries
// the HEAD sha it was produced for — so the sweep has to be the last thing that runs after the final
// commit (docs/DEFINITION_OF_DONE.md).
import { readFileSync, existsSync } from "node:fs";
import { execFileSync } from "node:child_process";

let input = {};
try {
  input = JSON.parse(readFileSync(0, "utf8") || "{}");
} catch {
  process.exit(0); // not hook JSON — never block on our own parse error
}

const tool = input.tool_name ?? "";
const cmd = String(input.tool_input?.command ?? "");
// Only an INVOCATION — at the start of the command, or after ; && || | or a newline. The same
// words inside a commit message, an echo or a heredoc body are not a PR being opened.
const isPrCreate = /(^|[;&|]\s*|\n\s*)gh\s+pr\s+create\b/.test(cmd) || /create_pull_request$/.test(tool);
if (!isPrCreate) process.exit(0);

function block(reason) {
  process.stderr.write(
    `BLOCKED — pre-PR sweep required before creating a PR.\n${reason}\n` +
      `Run: .\\scripts\\pre-pr-sweep.ps1   (docs/DEFINITION_OF_DONE.md → "The gate")\n`,
  );
  process.exit(2);
}

const git = (...args) => execFileSync("git", args, { encoding: "utf8" }).trim();
const root = git("rev-parse", "--show-toplevel");
const receiptPath = `${root}/build/sweep-receipt.json`;
if (!existsSync(receiptPath)) block("No build/sweep-receipt.json — the sweep has not passed on this checkout.");

let receipt;
try {
  receipt = JSON.parse(readFileSync(receiptPath, "utf8"));
} catch {
  block("build/sweep-receipt.json is unreadable — re-run the sweep.");
}
const head = git("rev-parse", "HEAD");
if (receipt.sha !== head)
  block(`Receipt is for ${receipt.sha.slice(0, 10)} but HEAD is ${head.slice(0, 10)} — commits landed after the sweep; re-run it.`);
const dirty = git("status", "--porcelain", "--untracked-files=no");
if (dirty) block(`Uncommitted tracked changes:\n${dirty}\nCommit them, then re-run the sweep.`);
if (receipt.treeClean === false) block("The receipt was produced on a dirty tree — re-run the sweep after committing.");

if (receipt.docsOnly === true) {
  // A -DocsOnly receipt skipped the build/lint/test gates, so it only covers a docs-only diff
  // (the #109/#119/#144 precedent). Anything else on the branch needs the full sweep.
  let base = "";
  for (const ref of ["origin/main", "main"]) {
    try {
      base = git("merge-base", ref, "HEAD");
      break;
    } catch {
      base = "";
    }
  }
  if (base) {
    const changed = git("diff", "--name-only", `${base}..HEAD`).split("\n").filter(Boolean);
    const code = changed.filter((f) => !f.endsWith(".md"));
    if (code.length)
      block(`The receipt is -DocsOnly but the branch changes non-Markdown files:\n${code.slice(0, 10).join("\n")}\nRun the full sweep.`);
  }
}

const notes = [];
if (receipt.inspectCode !== "passed") notes.push("Inspect Code (Engine 2) was SKIPPED — say so in the PR description.");
if (receipt.connected === false) notes.push("Instrumented tests were SKIPPED — say so in the PR description.");
if (notes.length) process.stderr.write(`sweep receipt OK for ${head.slice(0, 10)}; notes: ${notes.join(" ")}\n`);
process.exit(0);
