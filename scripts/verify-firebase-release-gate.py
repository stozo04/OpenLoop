import os
from pathlib import Path
import subprocess
import time


root = Path(__file__).resolve().parents[1]
config = root / "app/google-services.json"
backup = config.with_name("google-services.json.guard-test.tmp")
if backup.exists():
    raise SystemExit(f"Restore {backup} before running this check again.")

wrapper = "gradlew.bat" if os.name == "nt" else "./gradlew"
had_config = config.exists()
if had_config:
    config.rename(backup)
try:
    releases = [
        subprocess.run(
            [str(root / wrapper), task, "--console=plain"],
            cwd=root, capture_output=True, text=True, check=False,
        )
        for task in (":app:assembleRelease", ":app:bundleRelease")
    ]
    releases += [
        subprocess.run(
            [str(root / wrapper), task, "-PopenloopVerification=true", "--console=plain"],
            cwd=root, capture_output=True, text=True, check=False,
        )
        for task in (":app:assembleRelease", ":app:bundleRelease")
    ]
    debug = subprocess.run(
        [str(root / wrapper), ":app:preDebugBuild", "--console=plain"],
        cwd=root, capture_output=True, text=True, check=False,
    )
finally:
    if had_config:
        backup.rename(config)

for release in releases:
    assert release.returncode != 0, f"{release.args[1]} accepted missing Firebase configuration."
    assert "Release builds require app/google-services.json" in release.stdout + release.stderr, (
        release.stdout + release.stderr
    )
assert debug.returncode == 0, debug.stdout + debug.stderr
if had_config:
    bundle = root / "app/build/outputs/bundle/release/app-release.aab"
    bundle_backup = bundle.with_suffix(".aab.guard-test.tmp")
    if bundle_backup.exists():
        raise SystemExit(f"Restore {bundle_backup} before running this check again.")
    if bundle.exists():
        bundle.rename(bundle_backup)
    try:
        verification_bundle = subprocess.run(
            [str(root / wrapper), ":app:bundleRelease", "-PopenloopVerification=true", "--console=plain"],
            cwd=root, capture_output=True, text=True, check=False,
        )
        assert verification_bundle.returncode != 0, "Verification mode produced a shipping bundle."
        assert "Verification builds cannot produce release bundles" in (
            verification_bundle.stdout + verification_bundle.stderr
        ), verification_bundle.stdout + verification_bundle.stderr
        assert not bundle.exists(), "Verification guard ran after an AAB was produced."
    finally:
        if bundle.exists():
            bundle.rename(root / f"build/unexpected-verification-bundle-{time.time_ns()}.aab")
        if bundle_backup.exists():
            bundle_backup.rename(bundle)
    print("PASS: verification mode rejects shipping bundles with Firebase configured.")
else:
    print("NOT RUN: configured verification-bundle check needs app/google-services.json.")
print("PASS: missing Firebase configuration blocks normal and verification release APKs and bundles; debug preparation works.")
print("Original Firebase configuration restored.")
