#!/usr/bin/env python3
"""Run shipped OpenLoop verification loops against one emulator.

A loop is helpers/<name>_loop.py. This runner selects which shipped loops to
execute and runs them one after another on a single adb serial. It does not
invent Compose tests and it does not edit a loop to make a fail look like a pass.

    python scripts/run-verification-loops.py              # --changed vs origin/main
    python scripts/run-verification-loops.py --all
    python scripts/run-verification-loops.py --only onboarding
    python scripts/run-verification-loops.py --list

Windows: `python` or `py -3`. Git Bash `python3` is often `Scripts\\python3.exe` and dies
with `No module named 'encodings'`.

Selection (--changed):

- Match changed paths against each loop's `paths` substrings.
- If `app/src/main/` is in the diff and nothing matched, run every shipped loop
  (unknown surface, don't skip).
- Docs / tests / skills with no loop script change select nothing.

One emulator. Do not overlap this process with connectedDebugAndroidTest.
Overlap with lint, JVM tests, and text gates is the point — start this as soon
as app-debug.apk exists.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HELPERS = ROOT / ".cursor" / "skills" / "verify-openloop" / "helpers"

# Registry: id -> path substrings that mean "this loop is in scope".
# create-verifier must add a row when it ships a new helpers/<id>_loop.py.
LOOPS: tuple[dict[str, object], ...] = (
    {
        "id": "onboarding",
        "script": "onboarding_loop.py",
        "paths": (
            "OnboardingScreen",
            "onboarding_loop.py",
            "features/onboarding.md",
            "UserPreferencesRepository",
            "openloop_preferences",
            "has_completed_onboarding",
            "res/raw/onboarding",
        ),
    },
)


def discover_scripts(helpers_dir: Path = HELPERS) -> list[Path]:
    if not helpers_dir.is_dir():
        return []
    return sorted(p for p in helpers_dir.glob("*_loop.py") if p.is_file())


def registry_ids() -> list[str]:
    return [str(row["id"]) for row in LOOPS]


def script_id(path: Path) -> str:
    name = path.name
    if name.endswith("_loop.py"):
        return name[: -len("_loop.py")]
    return path.stem


def changed_files(repo: Path = ROOT, base_ref: str = "origin/main") -> list[str] | None:
    """Relative paths changed on this branch vs merge-base. None if git cannot say."""
    merge = subprocess.run(
        ["git", "merge-base", base_ref, "HEAD"],
        cwd=repo,
        capture_output=True,
        text=True,
    )
    if merge.returncode != 0:
        merge = subprocess.run(
            ["git", "merge-base", "main", "HEAD"],
            cwd=repo,
            capture_output=True,
            text=True,
        )
    if merge.returncode != 0:
        return None
    base = merge.stdout.strip()
    diff = subprocess.run(
        ["git", "diff", "--name-only", base, "HEAD"],
        cwd=repo,
        capture_output=True,
        text=True,
        check=True,
    )
    dirty = subprocess.run(
        ["git", "diff", "--name-only", "HEAD"],
        cwd=repo,
        capture_output=True,
        text=True,
        check=True,
    )
    names = []
    seen: set[str] = set()
    for line in (diff.stdout + dirty.stdout).splitlines():
        path = line.strip().strip('"')
        if path and path not in seen:
            seen.add(path)
            names.append(path)
    return names


def path_matches(changed: list[str], needles: tuple[str, ...] | list[str]) -> bool:
    for rel in changed:
        for needle in needles:
            if needle in rel:
                return True
    return False


def app_main_changed(changed: list[str]) -> bool:
    return any(rel.startswith("app/src/main/") for rel in changed)


def select_loop_ids(
    changed: list[str] | None,
    *,
    shipped: list[str],
    registry: tuple[dict[str, object], ...] = LOOPS,
    mode: str = "changed",
    only: list[str] | None = None,
) -> list[str]:
    """Return loop ids to run, intersecting with scripts that actually exist."""
    shipped_set = set(shipped)
    if only is not None:
        missing = [name for name in only if name not in shipped_set]
        if missing:
            raise ValueError(f"no shipped loop named {missing[0]}")
        return [name for name in only if name in shipped_set]

    if mode == "all" or changed is None:
        return [name for name in shipped]

    selected: list[str] = []
    seen: set[str] = set()
    for row in registry:
        loop_id = str(row["id"])
        if loop_id not in shipped_set:
            continue
        needles = tuple(str(n) for n in row["paths"])  # type: ignore[arg-type]
        if path_matches(changed, needles) and loop_id not in seen:
            selected.append(loop_id)
            seen.add(loop_id)

    if app_main_changed(changed):
        all_needles: list[str] = []
        for row in registry:
            all_needles.extend(str(n) for n in row["paths"])  # type: ignore[arg-type]
        main_files = [rel for rel in changed if rel.startswith("app/src/main/")]
        unmapped = [rel for rel in main_files if not path_matches([rel], all_needles)]
        if unmapped or not selected:
            return [name for name in shipped]

    extras = [name for name in shipped if name not in {str(r["id"]) for r in registry}]
    if extras and app_main_changed(changed):
        for name in extras:
            if name not in seen:
                selected.append(name)
                seen.add(name)
    return selected


def run_one(script: Path) -> int:
    print(f"== loop {script_id(script)} ({script})", flush=True)
    proc = subprocess.run([sys.executable, str(script)], cwd=ROOT)
    return proc.returncode


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--all", action="store_true", help="run every shipped *_loop.py")
    group.add_argument("--changed", action="store_true", help="select from git diff (default)")
    parser.add_argument("--only", help="comma-separated loop ids")
    parser.add_argument("--list", action="store_true", help="print shipped ids and exit")
    args = parser.parse_args(argv)

    scripts = discover_scripts()
    shipped = [script_id(p) for p in scripts]
    by_id = {script_id(p): p for p in scripts}

    if args.list:
        if not shipped:
            print("no shipped loops")
            return 0
        for name in shipped:
            print(name)
        return 0

    only = [part.strip() for part in args.only.split(",") if part.strip()] if args.only else None
    mode = "all" if args.all else "changed"
    changed = None if (mode == "all" or only) else changed_files()
    if mode == "changed" and only is None and changed is None:
        print("git merge-base unavailable; running all shipped loops", flush=True)
        mode = "all"

    try:
        ids = select_loop_ids(changed, shipped=shipped, mode=mode, only=only)
    except ValueError as exc:
        print(f"FAIL {exc}")
        return 1

    if not ids:
        print("PASS loops=none (nothing in the diff maps to a shipped loop)")
        return 0

    failed: list[str] = []
    for name in ids:
        script = by_id[name]
        code = run_one(script)
        if code != 0:
            failed.append(name)

    if failed:
        print(f"FAIL loops={','.join(failed)} — fix the product, not the loop")
        return 1
    print(f"PASS loops={','.join(ids)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
