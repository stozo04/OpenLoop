#!/usr/bin/env python3
"""Cheap contract checks for the pre-PR sweep's orchestration."""
from pathlib import Path
import json
import subprocess
import tempfile


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
    assert "Remove-ReportDirectory $unitResultsDir" in build
    assert "Run-Gradle" not in section(build, 'Gate "3.', 'Gate "4.')
    assert "Run-Gradle" not in section(build, 'Gate "4.', "}\n}\n}")

    # A green receipt must say whether the run was cold and carry every gate's measured time.
    assert "cleanBuild         = [bool]$Clean" in source
    assert "durationSec        = $timings" in source
    assert "Remove-ReportDirectory (Join-Path $root" in source

    # Execute the real report parser and deletion guard on isolated report files.
    functions = section(source, "function Sum-JUnit", "# ---------------------------------------------------------------------------- build gates")
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        reports = root / "reports"
        reports.mkdir()
        result = reports / "TEST-probe.xml"
        result.write_text(
            '<testsuite tests="3" failures="1" errors="0" skipped="1" time="1.5">'
            '<testcase name="pass"/><testcase name="red"><failure/></testcase>'
            '<testcase name="skip"><skipped/></testcase></testsuite>', encoding="utf-8"
        )
        script = root / "check.ps1"
        script.write_text(
            "$errors = $null\n"
            "[System.Management.Automation.Language.Parser]::ParseFile($args[0], [ref]$null, [ref]$errors) | Out-Null\n"
            "if ($errors.Count) { $errors | Write-Error; exit 1 }\n", encoding="utf-8"
        )
        subprocess.run(["pwsh", "-NoProfile", "-File", str(script), str(SWEEP)], check=True)
        prefix = "$ErrorActionPreference = 'Continue'\n" + functions + "\n"
        script.write_text(prefix + 'Sum-JUnit $args[0] | ConvertTo-Json\n', encoding="utf-8")
        process = subprocess.run(["pwsh", "-NoProfile", "-File", str(script), str(reports)],
                                 capture_output=True, text=True, check=True)
        assert json.loads(process.stdout) == {
            "Tests": 3, "Failures": 1, "Errors": 0, "Skipped": 1, "TestTimeSec": 1.5
        }
        result.write_text('<testsuite tests="4" failures="0" errors="0"><testcase/></testsuite>',
                          encoding="utf-8")
        assert subprocess.run(["pwsh", "-NoProfile", "-File", str(script), str(reports)],
                              capture_output=True).returncode != 0
        script.write_text(prefix + '$root = $args[0]\nRemove-ReportDirectory $args[1]\n',
                          encoding="utf-8")
        assert subprocess.run(["pwsh", "-NoProfile", "-File", str(script), str(root), str(root.parent)],
                              capture_output=True).returncode != 0
        assert result.exists()
        subprocess.run(["pwsh", "-NoProfile", "-File", str(script), str(root), str(reports)], check=True)
        assert not reports.exists()
        script.write_text(prefix + """
$proof = [pscustomobject]@{
    status = 'passed'; apkSha256 = 'apk'; selected = @('one', 'two'); skipped = @()
    executed = @([pscustomobject]@{name='one';exitCode=0}, [pscustomobject]@{name='two';exitCode=0})
}
$states = @(Test-FullLoopReceipt $proof @('one', 'two') 'apk')
$states += Test-FullLoopReceipt $proof @('one', 'two') 'different'
$states += Test-FullLoopReceipt $proof @() 'apk'
$proof.selected = @('one')
$states += Test-FullLoopReceipt $proof @('one', 'two') 'apk'
$proof.selected = @('one', 'two')
$proof.executed[1].exitCode = 1
$states += Test-FullLoopReceipt $proof @('one', 'two') 'apk'
$proof.executed[1].exitCode = 0
$proof.skipped = @('two')
$states += Test-FullLoopReceipt $proof @('one', 'two') 'apk'
$states | ConvertTo-Json
""", encoding="utf-8")
        process = subprocess.run(["pwsh", "-NoProfile", "-File", str(script)],
                                 capture_output=True, text=True, check=True)
        assert json.loads(process.stdout) == [True, False, False, False, False, False]

    print("pre-pr sweep contract PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
