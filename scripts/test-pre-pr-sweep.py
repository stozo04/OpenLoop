#!/usr/bin/env python3
"""Cheap contract checks for the pre-PR sweep's orchestration."""
from pathlib import Path


SWEEP = Path(__file__).resolve().parent / "pre-pr-sweep.ps1"


def section(source, start, end):
    return source[source.index(start):source.index(end)]


def main():
    source = SWEEP.read_text(encoding="utf-8")

    # Frequent text failures must surface before Gradle; device users must wait for the build.
    assert source.index('Gate "6a.') < source.index("& $buildGates")
    assert source.index("& $buildGates") < source.index('Gate "5b.') < source.index('Gate "5.')

    build = section(source, "$buildGates = {", "# ---------------------------------------------------------------------------- text gates")
    for task in ("assembleDebug", "assembleRelease", ":app:lintDebug", ":app:testDebugUnitTest"):
        assert task in build
    assert build.count("Run-Gradle") == 1
    assert '"--continue"' in build
    assert "Remove-Item -LiteralPath $unitResultsDir -Recurse -Force" in build
    assert "Run-Gradle" not in section(build, 'Gate "3.', 'Gate "4.')
    assert "Run-Gradle" not in section(build, 'Gate "4.', "}\n}\n}")

    # A green receipt must say whether the run was cold and carry every gate's measured time.
    assert "cleanBuild         = [bool]$Clean" in source
    assert "durationSec        = $timings" in source

    print("pre-pr sweep contract PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
