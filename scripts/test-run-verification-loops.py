#!/usr/bin/env python3
"""Self-check for scripts/run-verification-loops.py — selection only, no adb.

    python scripts/test-run-verification-loops.py

Pins the DoD rule that a Kotlin change under app/src/main with no registry hit
still runs every shipped loop, and that a docs-only diff runs none.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
runner = __import__("run-verification-loops")

REG = (
    {
        "id": "onboarding",
        "script": "onboarding_loop.py",
        "paths": ("OnboardingScreen", "onboarding_loop.py", "features/onboarding.md"),
    },
    {
        "id": "capture-mode",
        "script": "capture-mode_loop.py",
        "paths": ("CaptureMode", "photo-capture.md"),
    },
)
SHIPPED = ["onboarding", "capture-mode"]


def select(changed, **kwargs):
    return runner.select_loop_ids(
        changed,
        shipped=kwargs.pop("shipped", SHIPPED),
        registry=REG,
        **kwargs,
    )


def main():
    print("testing run-verification-loops.py")

    got = select(["docs/TEST_COVERAGE.md", "README.md"])
    assert got == [], got
    print("  ok  docs only selects nothing")

    got = select(["app/src/main/java/io/github/stozo04/openloop/ui/OnboardingScreen.kt"])
    assert got == ["onboarding"], got
    print("  ok  OnboardingScreen selects onboarding only")

    got = select(["app/src/main/java/io/github/stozo04/openloop/ui/CameraScreen.kt"])
    assert got == SHIPPED, got
    print("  ok  unmapped app/src/main falls back to all shipped")

    got = select(["docs/a.md"], mode="all")
    assert got == SHIPPED, got
    print("  ok  --all ignores the diff")

    got = select(None, mode="all", only=["onboarding"])
    assert got == ["onboarding"], got
    print("  ok  --only filters")

    try:
        select(None, only=["not-a-loop"])
    except ValueError:
        pass
    else:
        raise AssertionError("expected ValueError")
    print("  ok  unknown --only raises")

    got = select(["app/src/test/java/FooTest.kt"])
    assert got == [], got
    print("  ok  unit-test path is not app/src/main fallback")

    got = select([".cursor/skills/verify-openloop/helpers/onboarding_loop.py"])
    assert got == ["onboarding"], got
    print("  ok  loop script itself is in scope")

    print("ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
