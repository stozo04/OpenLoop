import os
from pathlib import Path
import subprocess


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
print("PASS: missing Firebase configuration blocks release APKs and bundles; debug preparation works.")
print("Original Firebase configuration restored.")
