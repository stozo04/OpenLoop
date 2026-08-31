#!/usr/bin/env python3
"""Keep the per-harness skill packages byte-identical.

The owner drives this repo with three LLM harnesses, and each one auto-discovers skills only
under its own directory — `.claude/skills/`, `.cursor/skills/`, `.codex/skills/`. The content is
the same project knowledge, so the three trees are copies of ONE thing and must not drift: a
skill fixed for one LLM that stays broken for the other two is worse than not fixing it, because
nothing says which copy is current. Same rule as `docs/DEFINITION_OF_DONE.md` M3 for the shared
instruction files, one level down (M5).

Scope is `skills/**` only. `settings.json` is deliberately NOT compared — it is harness-specific
(Claude's carries marketplaces, plugins and hooks; the others a plugin stub) — and
`.claude/commands/` has no counterpart in the other two.

    python scripts/sync-harness-skills.py                    # --check is the default: exit 1 on drift
    python scripts/sync-harness-skills.py --fix --from claude  # copy one tree over the other two

`--fix` has no default source on purpose. Whichever harness was edited is the source of truth,
and guessing gets it backwards half the time — silently reverting the edit this script exists to
propagate.
"""
import argparse
import filecmp
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HARNESSES = ("claude", "cursor", "codex")
SUBTREE = "skills"


def tracked(harness):
    """Relative paths under <harness>/skills that git tracks, mapped to their absolute path.

    Driven by `git ls-files` rather than a directory walk so gitignored working files —
    `.claude/worktrees/` above all — can never be mistaken for drift.
    """
    top = f".{harness}/{SUBTREE}"
    out = subprocess.run(["git", "ls-files", "-z", top], cwd=ROOT, check=True,
                         capture_output=True, text=True).stdout
    return {p[len(top) + 1:]: ROOT / p for p in out.split("\0") if p}


def drift():
    """(rel_path, {harness: 'same'|'differs'|'missing'}) for every path that is not identical."""
    trees = {h: tracked(h) for h in HARNESSES}
    base = HARNESSES[0]
    found = []
    for rel in sorted(set().union(*(t.keys() for t in trees.values()))):
        state = {}
        for h in HARNESSES:
            # `git ls-files` still lists a tracked file that has been deleted on disk, so
            # existence is checked separately — an unstaged delete is drift like any other.
            if rel not in trees[h] or not trees[h][rel].exists():
                state[h] = "missing"
            elif rel not in trees[base] or not trees[base][rel].exists() or h == base:
                state[h] = "same"
            else:
                # shallow=False: compare contents, never size + modification time — a checkout rewrites those.
                state[h] = "same" if filecmp.cmp(trees[base][rel], trees[h][rel], shallow=False) else "differs"
        if set(state.values()) != {"same"}:
            found.append((rel, state))
    return trees, found


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="exit 1 if the trees differ (default)")
    ap.add_argument("--fix", action="store_true", help="copy --from over the other harnesses")
    ap.add_argument("--from", dest="source", choices=HARNESSES,
                    help="the harness that was edited; required with --fix")
    args = ap.parse_args(argv)

    trees, found = drift()
    total = len(trees[HARNESSES[0]])

    if not args.fix:
        if not found:
            print(f"harness skills in sync ({total} files x {len(HARNESSES)} harnesses)")
            return 0
        print(f"{len(found)} path(s) differ between {', '.join('.' + h for h in HARNESSES)}:")
        for rel, state in found:
            print(f"  {SUBTREE}/{rel}")
            for h in HARNESSES:
                print(f"      .{h}: {state[h]}")
        print("\nThe harness you edited is the source of truth. Propagate it with:")
        print("  python scripts/sync-harness-skills.py --fix --from <claude|cursor|codex>")
        return 1

    if not args.source:
        ap.error("--fix requires --from <claude|cursor|codex>: name the harness you edited")
    if not found:
        print(f"harness skills already in sync ({total} files x {len(HARNESSES)} harnesses)")
        return 0

    src_tree = trees[args.source]
    copied = removed = 0
    for rel, _ in found:
        for h in HARNESSES:
            if h == args.source:
                continue
            dst = ROOT / f".{h}" / SUBTREE / rel
            if rel not in src_tree or not src_tree[rel].exists():
                # Deleted in the source: mirror the delete, or the next --check flags it forever.
                if dst.exists():
                    dst.unlink()
                    removed += 1
                    print(f"  removed .{h}/{SUBTREE}/{rel}")
                continue
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src_tree[rel], dst)
            copied += 1
            print(f"  wrote   .{h}/{SUBTREE}/{rel}")
    print(f"\nsynced from .{args.source}: {copied} copied, {removed} removed — `git add` the result")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
