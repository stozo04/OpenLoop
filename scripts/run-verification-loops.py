#!/usr/bin/env python3
"""Run every shipped autonomous installed-APK verifier.

`--changed` is retained as the stable pre-PR command. Verifiers are discovered
from the synchronized verify-openloop helper tree; no registry or roadmap.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HELPERS = ROOT / ".cursor/skills/verify-openloop/helpers"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--changed", action="store_true", help="compatibility flag")
    parser.parse_args(argv)

    loops = sorted(HELPERS.glob("*_loop.py"))
    failed: list[str] = []
    for loop in loops:
        name = loop.stem.removesuffix("_loop")
        print(f"== loop {name} ({loop})", flush=True)
        if subprocess.run([sys.executable, str(loop)], cwd=ROOT).returncode:
            failed.append(name)

    if failed:
        print(f"FAIL loops={','.join(failed)} — fix the product, not the loop")
        return 1
    names = ",".join(loop.stem.removesuffix("_loop") for loop in loops)
    print(f"PASS loops={names or 'none'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
