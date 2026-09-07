#!/usr/bin/env python3
"""Run all installed-APK verifiers, or explicitly select loops for local iteration."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HELPERS = ROOT / ".cursor/skills/verify-openloop/helpers"
APK_REL = "app/build/outputs/apk/debug/app-debug.apk"


def identities() -> dict[str, str]:
    digest = hashlib.sha256()
    for path in sorted(HELPERS.glob("*.py")):
        digest.update(path.name.encode())
        digest.update(b"\0")
        digest.update(path.read_bytes())
    return {
        "apkSha256": hashlib.sha256((ROOT / APK_REL).read_bytes()).hexdigest(),
        "helperSha256": digest.hexdigest(),
        "runnerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--loops", nargs="+", help="explicit loop names; local coverage only")
    selection.add_argument("--all", action="store_true", help="run all loops (default)")
    selection.add_argument("--changed", action="store_true", help="compatibility alias for --all")
    parser.add_argument("--list", action="store_true", help="list names without using a device")
    parser.add_argument("--report", type=Path, default=ROOT / "build/verification-loops-receipt.json")
    args = parser.parse_args(argv)
    loops = {path.stem.removesuffix("_loop"): path for path in sorted(HELPERS.glob("*_loop.py"))}
    if args.list:
        print("\n".join(loops))
        return 0 if loops else 1

    started = time.monotonic()
    report = {
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "status": "failed", "discovered": list(loops), "selected": [],
        "executed": [], "skipped": [],
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    # Invalidate an earlier success before selection, setup, or device access can fail.
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    try:
        if not loops:
            raise ValueError("no verifier loops discovered")
        selected = list(dict.fromkeys(args.loops or loops))
        unknown = sorted(set(selected) - loops.keys())
        if unknown:
            raise ValueError(f"unknown loops: {','.join(unknown)}")
        report["selected"] = selected
        report["skipped"] = [{"name": name, "reason": "not selected for local iteration"}
                             for name in loops if name not in selected]
        if not (ROOT / APK_REL).is_file():
            raise ValueError(f"debug APK missing: {ROOT / APK_REL}; build assembleDebug first")
        original_identity = identities()
        report.update(original_identity)
        report["sourceSha"] = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, timeout=30).strip()
        report["sourceDirty"] = bool(subprocess.check_output(
            ["git", "status", "--porcelain"], cwd=ROOT, text=True, timeout=30).strip())
        env = os.environ.copy()
        verify_serial = env.get("VERIFY_SERIAL", "").strip()
        android_serial = env.get("ANDROID_SERIAL", "").strip()
        if verify_serial and android_serial and verify_serial != android_serial:
            raise ValueError("VERIFY_SERIAL and ANDROID_SERIAL conflict; select one device")
        serial = verify_serial or android_serial
        if not serial:
            sys.path.insert(0, str(HELPERS))
            from verify_common import resolve_serial
            serial = resolve_serial()
        env["VERIFY_SERIAL"] = env["ANDROID_SERIAL"] = serial
        report["serial"] = serial
        evidence_base = Path(env.get("VERIFY_EVIDENCE_DIR") or args.report.parent)
        evidence_base.mkdir(parents=True, exist_ok=True)
        evidence = Path(tempfile.mkdtemp(prefix="verification-", dir=evidence_base))
        report["evidenceDir"] = str(evidence)
        for name in selected:
            env["VERIFY_EVIDENCE_DIR"] = str(evidence / name)
            print(f"== loop {name} ({loops[name]})", flush=True)
            loop_start = time.monotonic()
            code = subprocess.run([sys.executable, str(loops[name])], cwd=ROOT, env=env, timeout=900).returncode
            report["executed"].append({"name": name, "exitCode": code,
                                       "status": "passed" if code == 0 else "failed",
                                       "durationSec": round(time.monotonic() - loop_start, 3)})
        if identities() != original_identity:
            raise ValueError("APK, runner, or helper files changed during verification; rerun")
        if any(item["exitCode"] for item in report["executed"]):
            raise ValueError("one or more verifier loops failed")
        report["status"] = "passed"
    except (OSError, ValueError, subprocess.SubprocessError, SystemExit) as exc:
        report["error"] = str(exc)
    finally:
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        report["durationSec"] = round(time.monotonic() - started, 3)
        content = json.dumps(report, indent=2) + "\n"
        args.report.write_text(content, encoding="utf-8")
        if report.get("evidenceDir"):
            (Path(report["evidenceDir"]) / "receipt.json").write_text(content, encoding="utf-8")
    print(f"Report: {args.report}")
    if report["status"] != "passed":
        print(f"FAIL {report.get('error', 'verification failed')}")
        return 1
    print(f"PASS loops={','.join(report['selected'])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
