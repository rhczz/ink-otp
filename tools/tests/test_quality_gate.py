"""Mutation checks: bad evidence must turn the release-quality gate red."""
import importlib.util
import contextlib
import io
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("quality_gate", ROOT / "tools/quality_gate.py")
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


def elf(alignment=16384, bits=64):
    data = bytearray(120 if bits == 64 else 84)
    data[:6] = b"\x7fELF" + bytes([2 if bits == 64 else 1, 1])
    if bits == 64:
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 1)
        struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, len(data), len(data), alignment)
    else:
        struct.pack_into("<I", data, 28, 52)
        struct.pack_into("<HH", data, 42, 32, 1)
        struct.pack_into("<IIIIIIII", data, 52, 1, 0, 0, 0, len(data), len(data), 5, alignment)
    return data


class QualityGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, name, content):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        return path

    def junit(self, extra="", count=2, attributes=""):
        cases = '<testcase name="a"/><testcase name="b"/>' if count else ""
        return self.write("tests/TEST-suite.xml", f'<testsuite tests="{count}" failures="0" errors="0" skipped="0" {attributes}>{cases}{extra}</testsuite>')

    def test_junit_accepts_real_testcases_and_rejects_missing_or_zero_reports(self):
        self.assertTrue(gate.inspect_junit(self.root / "tests", 2)[0])
        self.junit()
        self.assertEqual([], gate.inspect_junit(self.root / "tests", 2)[0])
        self.junit(count=0)
        self.assertTrue(gate.inspect_junit(self.root / "tests", 2)[0])

    def test_junit_rejects_failed_error_skipped_and_dishonest_counts(self):
        for marker in ["failure", "error", "skipped"]:
            with self.subTest(marker=marker):
                self.junit(f'<testcase name="bad"><{marker}/></testcase>', 3)
                self.assertTrue(gate.inspect_junit(self.root / "tests", 2)[0])
        self.junit(count=2)
        self.assertTrue(gate.inspect_junit(self.root / "tests", 3)[0])
        self.write("tests/TEST-suite.xml", '<testsuite tests="200" failures="0" errors="0" skipped="0"/>')
        self.assertTrue(gate.inspect_junit(self.root / "tests", 2)[0])

    def test_junit_rejects_malformed_xml_and_nonzero_summary(self):
        self.write("tests/TEST-suite.xml", "broken")
        self.assertTrue(gate.inspect_junit(self.root / "tests", 2)[0])
        self.write("tests/TEST-suite.xml", '<testsuite tests="2" failures="1" errors="0" skipped="0"><testcase/><testcase/></testsuite>')
        self.assertTrue(gate.inspect_junit(self.root / "tests", 2)[0])

    def test_coverage_rejects_missing_counters_empty_and_regressions(self):
        path = self.root / "coverage.xml"
        self.assertTrue(gate.inspect_coverage(path, {"LINE": 90, "BRANCH": 70})[0])
        self.write("coverage.xml", '<report><counter type="LINE" covered="90" missed="10"/><counter type="BRANCH" covered="70" missed="30"/></report>')
        self.assertEqual([], gate.inspect_coverage(path, {"LINE": 90, "BRANCH": 70})[0])
        for text in ['<report/>', '<report><counter type="LINE" covered="0" missed="0"/></report>',
                     '<report><counter type="LINE" covered="89" missed="11"/><counter type="BRANCH" covered="69" missed="31"/></report>']:
            self.write("coverage.xml", text)
            self.assertTrue(gate.inspect_coverage(path, {"LINE": 90, "BRANCH": 70})[0])

    def test_lint_rejects_missing_error_fatal_but_allows_warning(self):
        path = self.root / "lint.xml"
        self.assertTrue(gate.inspect_lint(path)[0])
        self.write("lint.xml", '<issues><issue severity="Warning"/></issues>')
        self.assertEqual([], gate.inspect_lint(path)[0])
        for severity in ["Error", "Fatal"]:
            self.write("lint.xml", f'<issues><issue severity="{severity}"/></issues>')
            self.assertTrue(gate.inspect_lint(path)[0])

    def manifest(self, permission="android.permission.CAMERA", debug="", required="false"):
        features = "".join(f'<uses-feature android:name="android.hardware.{name}" android:required="{required}"/>' for name in ["camera", "camera.any", "camera.autofocus"])
        return self.write("AndroidManifest.xml", f'<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-permission android:name="{permission}"/>{features}<application {debug}/></manifest>')

    def test_manifest_requires_offline_permission_allowlist_and_optional_camera(self):
        allowed = ["android.permission.CAMERA"]
        self.assertEqual([], gate.inspect_manifest(self.manifest(), allowed)[0])
        for permission in ["android.permission.INTERNET", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.RECORD_AUDIO"]:
            self.assertTrue(gate.inspect_manifest(self.manifest(permission), allowed)[0])
        self.assertTrue(gate.inspect_manifest(self.manifest(debug='android:debuggable="true"'), allowed)[0])
        self.assertTrue(gate.inspect_manifest(self.manifest(required="true"), allowed)[0])
        self.write("AndroidManifest.xml", "<manifest><application/></manifest>")
        self.assertTrue(gate.inspect_manifest(self.root / "AndroidManifest.xml", allowed)[0])

    def apk(self, alignment=16384, abis=("arm64-v8a", "armeabi-v7a", "x86", "x86_64"), omit=""):
        path = self.root / "app.apk"
        with zipfile.ZipFile(path, "w") as archive:
            for name in ["AndroidManifest.xml", "classes.dex"]:
                if name != omit:
                    archive.writestr(name, b"fixture")
            for abi in abis:
                for library in ["argon2", "jnidispatch", "datastore_shared_counter", "androidx.graphics.path"]:
                    archive.writestr(f"lib/{abi}/lib{library}.so", elf(alignment, 32 if abi in ["x86", "armeabi-v7a"] else 64))
        return path

    def test_apk_accepts_four_abis_and_sixteen_aligned_libraries(self):
        errors, info = gate.inspect_apk(self.apk(), 15 * 1024 * 1024, ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"])
        self.assertEqual([], errors)
        self.assertEqual(16, len(info["native_libraries"]))

    def test_apk_mutations_reject_size_missing_abi_manifest_dex_and_low_elf_alignment(self):
        abis = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
        self.assertTrue(gate.inspect_apk(self.apk(), 100, abis)[0])
        self.assertTrue(gate.inspect_apk(self.apk(alignment=4096), 15 * 1024 * 1024, abis)[0])
        self.assertTrue(gate.inspect_apk(self.apk(abis=["arm64-v8a"]), 15 * 1024 * 1024, abis)[0])
        for name in ["AndroidManifest.xml", "classes.dex"]:
            self.assertTrue(gate.inspect_apk(self.apk(omit=name), 15 * 1024 * 1024, abis)[0])

    def test_apk_rejects_truncated_invalid_elf_and_duplicate_entries(self):
        path = self.apk()
        with zipfile.ZipFile(path, "a") as archive:
            archive.writestr("lib/arm64-v8a/libbad.so", b"\x7fELF")
        self.assertTrue(gate.inspect_apk(path, 15 * 1024 * 1024, ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"])[0])
        path.write_bytes(b"not a zip")
        self.assertTrue(gate.inspect_apk(path, 15 * 1024 * 1024, ["arm64-v8a"])[0])

    def test_metadata_requires_unique_apk_matching_package_and_literal_versions(self):
        path = self.write("output-metadata.json", json.dumps({"applicationId": "io.github.rhczz.inkotp", "elements": [{"outputFile": "app.apk", "versionCode": 3, "versionName": "0.4.0-beta.1"}]}))
        self.assertEqual([], gate.inspect_metadata(path, self.root / "app.apk", "io.github.rhczz.inkotp")[0])
        path.write_text('{}')
        self.assertTrue(gate.inspect_metadata(path, self.root / "app.apk", "io.github.rhczz.inkotp")[0])

    def test_hygiene_catches_tracked_ignored_private_files_and_allows_public_fixtures(self):
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        self.write("spec/fixtures/public.json", '{"secret":"JBSWY3DPEHPK3PXP"}')
        self.assertEqual([], gate.inspect_hygiene(self.root)[0])
        for name in ["local-signing.properties", "dist/app.apk", "key.jks", "private.txt"]:
            self.write(name, "-----BEGIN PRIVATE KEY-----\nfixture\n")
            subprocess.run(["git", "-C", str(self.root), "add", "--", name], check=True)
        self.assertTrue(gate.inspect_hygiene(self.root)[0])

    def test_cli_returns_nonzero_for_missing_failed_skipped_low_coverage_oversized_and_misaligned_evidence(self):
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        config = {
            "package_id": "io.github.rhczz.inkotp",
            "modules": {"fixture": {"junit": "tests", "minimum_tests": 2, "coverage": "coverage.xml",
                                     "minimum_coverage": {"LINE": 90, "BRANCH": 70}}},
            "lint": ["lint.xml"], "manifests": ["AndroidManifest.xml"],
            "allowed_permissions": ["android.permission.CAMERA"],
            "apk": {"path": "app.apk", "metadata": "output-metadata.json", "max_bytes": 15 * 1024 * 1024,
                    "required_abis": ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]},
        }
        # The APK is an ignored build product, just as in the real repository.
        self.write(".gitignore", "*.apk\n")
        def baseline():
            self.junit()
            self.write("coverage.xml", '<report><counter type="LINE" covered="90" missed="10"/><counter type="BRANCH" covered="70" missed="30"/></report>')
            self.write("lint.xml", "<issues/>")
            self.manifest()
            self.apk()
            self.write("output-metadata.json", json.dumps({"applicationId": config["package_id"], "elements": [{"outputFile": "app.apk", "versionCode": 3, "versionName": "0.4.0-beta.1"}]}))
            config["apk"]["max_bytes"] = 15 * 1024 * 1024
        def invoke():
            self.write("ci.json", json.dumps(config))
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                return gate.main(["--root", str(self.root), "--config", "ci.json", "--report", "result.json"])
        baseline()
        self.assertEqual(0, invoke())
        mutations = {
            "missing": lambda: (self.root / "tests/TEST-suite.xml").unlink(),
            "failed": lambda: self.junit('<testcase><failure/></testcase>', 3),
            "skipped": lambda: self.junit('<testcase><skipped/></testcase>', 3),
            "coverage": lambda: self.write("coverage.xml", '<report><counter type="LINE" covered="89" missed="11"/><counter type="BRANCH" covered="69" missed="31"/></report>'),
            "oversized": lambda: config["apk"].update(max_bytes=100),
            "alignment": lambda: self.apk(alignment=4096),
        }
        for name, mutation in mutations.items():
            with self.subTest(mutation=name):
                baseline()
                mutation()
                self.assertEqual(1, invoke())
                self.assertFalse(json.loads((self.root / "result.json").read_text())["passed"])


if __name__ == "__main__":
    unittest.main()
