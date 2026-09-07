#!/usr/bin/env python3
"""Offline checks for verifier selection, fresh failure reports, and APK installation identity."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def must_fail(call):
    try:
        call()
    except SystemExit as exc:
        assert exc.code == 1
    else:
        raise AssertionError("expected failure")


def main():
    runner = load("verification_runner", ROOT / "scripts/run-verification-loops.py")
    common = load("verification_common", ROOT / ".codex/skills/verify-openloop/helpers/verify_common.py")
    with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
        root = Path(directory)
        helpers = root / "helpers"
        helpers.mkdir()
        alpha = helpers / "alpha_loop.py"
        beta = helpers / "beta_loop.py"
        alpha.write_text("pass\n", encoding="utf-8")
        beta.write_text("raise SystemExit(1)\n", encoding="utf-8")
        apk = root / runner.APK_REL
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b"test APK")
        report_path = root / "build/verification-loops-receipt.json"

        def report():
            return json.loads(report_path.read_text(encoding="utf-8"))

        with patch.multiple(runner, ROOT=root, HELPERS=helpers), patch.dict(
            os.environ, {"VERIFY_SERIAL": "emulator-5554", "ANDROID_SERIAL": "emulator-5554"}
        ), patch.object(runner.subprocess, "check_output", side_effect=lambda args, **kw: "head\n" if "rev-parse" in args else ""):
            # Runs a real child process; excluded failure is disclosed, selected failure is fatal.
            assert runner.main(["--loops", "alpha"]) == 0
            first = report()
            assert first["status"] == "passed" and first["selected"] == ["alpha"]
            assert [item["name"] for item in first["skipped"]] == ["beta"]
            assert first["executed"][0]["exitCode"] == 0
            assert runner.main(["--all"]) == 1
            assert report()["status"] == "failed"
            assert [item["exitCode"] for item in report()["executed"]] == [0, 1]
            assert report()["evidenceDir"] != first["evidenceDir"]
            # A failed selection replaces an earlier success before any child can run.
            report_path.write_text(json.dumps(first), encoding="utf-8")
            assert runner.main(["--loops", "typo"]) == 1
            assert report()["status"] == "failed" and report()["executed"] == []
            empty = root / "empty"
            empty.mkdir()
            with patch.object(runner, "HELPERS", empty):
                assert runner.main([]) == 1
                assert "no verifier" in report()["error"]
            apk.unlink()
            assert runner.main([]) == 1
            assert "APK missing" in report()["error"]
            before = report_path.read_bytes()
            assert runner.main(["--list"]) == 0
            assert report_path.read_bytes() == before
            apk.write_bytes(b"test APK")
            with patch.dict(os.environ, {"ANDROID_SERIAL": "emulator-5556"}):
                assert runner.main([]) == 1
                assert "conflict" in report()["error"]
            alpha.write_text("from pathlib import Path\nPath(__file__).write_text('pass\\n')\n", encoding="utf-8")
            assert runner.main(["--loops", "alpha"]) == 1
            assert "changed during" in report()["error"]

        expected = hashlib.sha256(apk.read_bytes()).hexdigest()
        success = subprocess.CompletedProcess([], 0, "Success", "")
        with patch.object(common, "repo_root", return_value=root), patch.object(
            common.subprocess, "run", return_value=success
        ) as install:
            with patch.object(common, "installed_apk_sha256", return_value=expected):
                common.ensure_installed("emulator-5554")
                install.assert_not_called()
            with patch.object(common, "installed_apk_sha256", side_effect=[None, expected]):
                common.ensure_installed("emulator-5554")
                assert install.call_count == 1
            with patch.object(common, "installed_apk_sha256", return_value="0" * 64):
                must_fail(lambda: common.ensure_installed("emulator-5554"))
            install.return_value = subprocess.CompletedProcess([], 1, "", "install failed")
            with patch.object(common, "installed_apk_sha256", return_value=None):
                must_fail(lambda: common.ensure_installed("emulator-5554"))
            apk.unlink()
            install.reset_mock()
            must_fail(lambda: common.ensure_installed("emulator-5554"))
            install.assert_not_called()

        with patch.object(common, "adb_out", return_value="package:/data/app/example/base.apk\n"), patch.object(
            common, "run_adb", return_value=subprocess.CompletedProcess([], 0, expected + "  base.apk\n", "")
        ):
            assert common.installed_apk_sha256("emulator-5554") == expected
            with patch.object(common, "adb_out", return_value="package:/base.apk\npackage:/split.apk\n"):
                assert common.installed_apk_sha256("emulator-5554") is None
            with patch.object(common, "run_adb", return_value=subprocess.CompletedProcess([], 1, "", "denied")):
                assert common.installed_apk_sha256("emulator-5554") is None
    print("verification loops PASS: selection, deliberate child failure, freshness, mutation, serial, APK identity")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
