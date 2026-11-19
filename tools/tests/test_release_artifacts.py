import importlib.util
import base64
import contextlib
import io
import json
import os
import plistlib
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("inkotp_release_artifacts", ROOT / "tools/release_artifacts.py")
release = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = release
spec.loader.exec_module(release)


class ReleaseArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.version = {"version_name": "0.4.0-beta.1", "version_code": 3}
        # Release scenarios use a fixed source tree, independent of the app's next version.
        source_temp = tempfile.TemporaryDirectory()
        self.addCleanup(source_temp.cleanup)
        source_root = Path(source_temp.name)
        (source_root / ".github").mkdir()
        (source_root / ".github/release.yaml").write_text((ROOT / ".github/release.yaml").read_text())
        version_source = source_root / release.VERSION_SOURCE
        version_source.parent.mkdir(parents=True)
        version_source.write_text('versionCode = 3\nversionName = "0.4.0-beta.1"\n')
        notes = source_root / "docs/releases/0.4.0-beta.1.md"
        notes.parent.mkdir(parents=True)
        notes.write_text("# Release test notes\n")
        source_tree = patch.object(release, "ROOT", source_root)
        source_tree.start()
        self.addCleanup(source_tree.stop)

    def test_tag_must_exactly_match_the_literal_android_version(self):
        self.assertTrue(release.validate_tag("v0.4.0-beta.1", self.version))
        for tag in ["0.4.0-beta.1", "v0.4.0", "v0.4.0-beta.1/other", "v0.4.0-beta.1\ncommand"]:
            with self.subTest(tag=tag), self.assertRaises(release.ReleaseError):
                release.validate_tag(tag, self.version)

    def test_gradle_version_parser_requires_unambiguous_literal_versions(self):
        text = 'defaultConfig {\n versionCode = 3\n versionName = "0.4.0-beta.1"\n}'
        self.assertEqual(self.version, release.parse_gradle_version(text))
        for invalid in [text + '\nversionCode = 4', text.replace('versionCode = 3', 'versionCode = 0'),
                        text.replace('"0.4.0-beta.1"', 'versionFromEnvironment()')]:
            with self.assertRaises(release.ReleaseError):
                release.parse_gradle_version(invalid)

    def test_history_rejects_existing_release_and_non_increasing_version_code(self):
        previous = {"v0.3.0-beta.1": {"version_name": "0.3.0-beta.1", "version_code": 2}}
        release.validate_history(self.version, "v0.4.0-beta.1", previous, [])
        with self.assertRaises(release.ReleaseError):
            release.validate_history(self.version, "v0.4.0-beta.1", previous,
                                     [{"tag_name": "v0.4.0-beta.1", "draft": True}])
        with self.assertRaises(release.ReleaseError):
            release.validate_history(self.version, "v0.4.0-beta.1",
                                     {"v0.5.0": {"version_name": "0.5.0", "version_code": 3}}, [])
        with self.assertRaises(release.ReleaseError):
            release.validate_history(self.version, "v0.4.0-beta.1", previous, [{"tag_name": "deleted-tag"}])

    def test_existing_platform_releases_are_accepted_only_with_their_historical_tags(self):
        legacy_tags = {"ios-v0.5.3-preview.2", "macos-v0.1.0-beta.2"}
        records = [{"tag_name": tag} for tag in legacy_tags]
        release.validate_history(self.version, "v0.4.0-beta.1", {}, records, legacy_tags)
        with self.assertRaisesRegex(release.ReleaseError, "cannot be matched"):
            release.validate_history(self.version, "v0.4.0-beta.1", {}, records, set())

    def test_permission_allowlist_rejects_network_storage_and_unknown_permissions(self):
        allowed = ["android.permission.CAMERA", "io.github.rhczz.inkotp.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"]
        release.validate_permissions(allowed, allowed)
        for permission in ["android.permission.INTERNET", "android.permission.READ_MEDIA_IMAGES", "android.permission.RECORD_AUDIO"]:
            with self.subTest(permission=permission), self.assertRaises(release.ReleaseError):
                release.validate_permissions([permission], allowed)

    def test_certificate_comparison_accepts_keytool_format_but_rejects_extra_signers(self):
        digest = "ab" * 32
        self.assertEqual(digest, release.normalize_certificate(":".join(["AB"] * 32)))
        self.assertEqual(digest, release.validate_certificate(f"Signer #1 certificate SHA-256 digest: {digest}\n", digest))
        for output in [f"Signer #1 certificate SHA-256 digest: {'cd' * 32}\n",
                       f"Signer #1 certificate SHA-256 digest: {digest}\nSigner #2 certificate SHA-256 digest: {digest}\n",
                       f"Signer #1 certificate DN: CN=Android Debug,O=Android,C=US\nSigner #1 certificate SHA-256 digest: {digest}\n"]:
            with self.assertRaises(release.ReleaseError):
                release.validate_certificate(output, digest)

    def test_badging_must_contain_identity_sdk_and_debug_status(self):
        info = release.parse_badging("package: name='io.github.rhczz.inkotp' versionCode='3' versionName='0.4.0-beta.1'\nsdkVersion:'26'\ntargetSdkVersion:'36'\napplication-debuggable\nuses-permission: name='android.permission.CAMERA'\n")
        self.assertEqual(3, info["version_code"])
        self.assertEqual(26, info["min_sdk"])
        self.assertTrue(info["debuggable"])
        self.assertEqual(["android.permission.CAMERA"], info["permissions"])
        with self.assertRaises(release.ReleaseError):
            release.parse_badging("package: name='missing-sdk' versionCode='3' versionName='0.4.0-beta.1'\n")

    def test_bundle_checksums_cover_exact_payload_and_reject_tampering(self):
        apk = self.root / "InkOTP-0.4.0-beta.1.apk"
        apk.write_bytes(b"signed artifact fixture")
        (self.root / "build-info.json").write_text("{}\n")
        (self.root / "release-notes.md").write_text("# Release fixture\n")
        names = [apk.name, "build-info.json", "release-notes.md"]
        release.write_checksums(self.root, names)
        release.verify_checksums(self.root, names)
        apk.write_bytes(b"tampered")
        with self.assertRaises(release.ReleaseError):
            release.verify_checksums(self.root, names)

    def test_checksum_manifest_cannot_smuggle_a_parent_path_or_unlisted_file(self):
        (self.root / "safe.apk").write_bytes(b"apk")
        (self.root / "SHA256SUMS").write_text(f"{'00' * 32}  ../private-key.jks\n")
        with self.assertRaises(release.ReleaseError):
            release.verify_checksums(self.root, ["safe.apk"])
        release.write_checksums(self.root, ["safe.apk"])
        (self.root / "unexpected.jks").write_bytes(b"must never upload")
        with self.assertRaises(release.ReleaseError):
            release.verify_checksums(self.root, ["safe.apk"])

    def test_unsigned_gate_does_not_accept_an_existing_v1_signature(self):
        apk = self.root / "unsigned.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"fixture")
            archive.writestr("META-INF/SIGNER.RSA", b"signature")
        with self.assertRaises(release.ReleaseError):
            release.reject_signature_blocks(apk)

    def test_every_signing_secret_is_required_and_no_replacement_is_generated(self):
        environment = {name: "fixture" for name in release.SECRETS}
        environment[release.SECRETS[0]] = base64.b64encode(b"fixture keystore").decode()
        self.assertEqual(b"fixture keystore", release.required_secrets(environment))
        for name in release.SECRETS:
            with self.subTest(missing=name), self.assertRaises(release.ReleaseError):
                release.required_secrets({key: value for key, value in environment.items() if key != name})
        for encoded in ["not base64", "", base64.b64encode(b"a" * (1024 * 1024 + 1)).decode()]:
            with self.subTest(encoded=encoded[:15]), self.assertRaises(release.ReleaseError):
                release.required_secrets({**environment, release.SECRETS[0]: encoded})

    def test_apk_version_sdk_identity_and_debug_status_must_all_match_policy(self):
        cfg = release.config()
        info = {**self.version, "application_id": cfg["application_id"],
                "min_sdk": 26, "target_sdk": 36, "debuggable": False,
                "permissions": ["android.permission.CAMERA"]}
        release.validate_apk_identity(info, self.version, cfg)
        for field, value in [("version_name", "0.4.0"), ("version_code", 2),
                             ("application_id", "io.github.rhczz.inkotp.debug"),
                             ("min_sdk", 25), ("target_sdk", 35), ("debuggable", True),
                             ("permissions", ["android.permission.INTERNET"])]:
            with self.subTest(field=field), self.assertRaises(release.ReleaseError):
                release.validate_apk_identity({**info, field: value}, self.version, cfg)

    def test_metadata_must_describe_the_single_unsigned_universal_apk(self):
        cfg = release.config()
        apk = self.root / "app-release-unsigned.apk"
        element = {"versionName": self.version["version_name"], "versionCode": 3,
                   "outputFile": apk.name, "filters": []}
        metadata = {"applicationId": cfg["application_id"], "elements": [element]}
        release.validate_output_metadata(metadata, self.version, cfg, apk)
        for replacement in [{"versionName": "0.4.0"}, {"versionCode": 2},
                            {"outputFile": "app-debug.apk"}, {"filters": ["arm64-v8a"]}]:
            with self.subTest(replacement=replacement), self.assertRaises(release.ReleaseError):
                release.validate_output_metadata({**metadata, "elements": [{**element, **replacement}]},
                                                 self.version, cfg, apk)
        with self.assertRaises(release.ReleaseError):
            release.validate_output_metadata({**metadata, "elements": [element, element]}, self.version, cfg, apk)

    def test_tag_validation_rejects_shell_metacharacters_before_any_external_command(self):
        for tag in ["v0.4.0-beta.1;touch bad", "v$(id)", "-v0.4.0-beta.1", "v0.4.0-beta.1`id`"]:
            with self.subTest(tag=tag), patch.object(release, "run") as commands:
                with self.assertRaises(release.ReleaseError):
                    release.preflight(tag, release.config())
                commands.assert_not_called()

    def test_prerelease_command_uses_verified_tag_notes_file_and_exact_assets(self):
        names = release.payload_names(self.version) + [
            "InkOTP-0.4.0-beta.1-macOS-universal.dmg", "InkOTP-iOS-Simulator.zip", "InkOTP-iOS-unsigned.ipa"
        ]
        args = release.publish_command("v0.4.0-beta.1", self.version, self.root, "rhczz/ink-otp", names)
        self.assertIn("--verify-tag", args)
        self.assertIn("--prerelease", args)
        self.assertIn("--notes-file", args)
        self.assertIn("--latest=false", args)
        self.assertEqual(set(names + ["SHA256SUMS"]),
                         {Path(arg).name for arg in args if arg.startswith(str(self.root))})
        stable = release.publish_command("v1.0.0", {"version_name": "1.0.0", "version_code": 20},
                                         self.root, "rhczz/ink-otp", names)
        self.assertNotIn("--prerelease", stable)
        self.assertNotIn("--clobber", args)

    def platform_artifacts(self, number, *, signed=False):
        output, android, macos, ios = [self.root / name for name in ["output", "android", "macos", "ios"]]
        for directory in [output, android, macos, ios]:
            directory.mkdir()
        for name in release.payload_names({"version_name": number}):
            (android / name).write_text("Android artifact fixture\n")
        dmg = macos / f"InkOTP-{number}-macOS-universal.dmg"
        dmg.write_bytes(b"DMG fixture")
        (macos / "SHA256SUMS.txt").write_text(f"{release.sha256(dmg)}  {dmg.name}\n")
        (macos / "signature.txt").write_text("developer-id-notarized\n" if signed else "adhoc\n")
        metadata = {
            "CFBundleIdentifier": "io.github.rhczz.inkotp",
            "CFBundleShortVersionString": number.split("-", 1)[0],
            "InkOTPReleaseVersion": number,
        }
        ipa = "InkOTP-iOS.ipa" if signed else "InkOTP-iOS-unsigned.ipa"
        for filename, plist in [
            ("InkOTP-iOS-Simulator.zip", "InkOTP.app/Info.plist"),
            (ipa, "Payload/InkOTP.app/Info.plist"),
        ]:
            with zipfile.ZipFile(ios / filename, "w") as archive:
                archive.writestr(plist, plistlib.dumps(metadata, fmt=plistlib.FMT_BINARY))
        return output, android, macos, ios

    def test_one_manifest_covers_all_platforms_and_notes_describe_unsigned_ios(self):
        directories = self.platform_artifacts(self.version["version_name"])
        with patch.object(release, "build_context", return_value={
            "commit": "b" * 40, "repository": "rhczz/ink-otp", "run_id": "5",
        }):
            names = release.assemble_distribution(*directories, self.version, ios_signed=False)
        output = directories[0]
        self.assertEqual(6, len(names))
        self.assertIn("InkOTP-iOS-unsigned.ipa", names)
        self.assertIn("未签名", (output / "release-notes.md").read_text())
        release.verify_checksums(output, names)

    def test_unified_bundle_rejects_wrong_ios_version_and_tampered_dmg(self):
        output, android, macos, ios = self.platform_artifacts(self.version["version_name"])
        dmg = next(macos.glob("*.dmg"))
        dmg.write_bytes(b"tampered")
        with self.assertRaisesRegex(release.ReleaseError, "checksum mismatch"):
            release.assemble_distribution(output, android, macos, ios, self.version, ios_signed=False)
        dmg.write_bytes(b"DMG fixture")
        with zipfile.ZipFile(ios / "InkOTP-iOS-unsigned.ipa", "w") as archive:
            archive.writestr("Payload/InkOTP.app/Info.plist", plistlib.dumps({
                "CFBundleIdentifier": "io.github.rhczz.inkotp",
                "CFBundleShortVersionString": "0.4.0",
                "InkOTPReleaseVersion": "0.4.0-beta.2",
            }))
        with self.assertRaisesRegex(release.ReleaseError, "shared version mismatch"):
            release.assemble_distribution(output, android, macos, ios, self.version, ios_signed=False)
        (ios / "InkOTP-iOS-unsigned.ipa").unlink()
        with self.assertRaisesRegex(release.ReleaseError, "missing files"):
            release.assemble_distribution(output, android, macos, ios, self.version, ios_signed=False)
        self.assertEqual([], list(output.iterdir()))

    def test_stable_bundle_requires_macos_notarization_and_signed_ios(self):
        stable = {"version_name": "0.5.4", "version_code": 12}
        directories = self.platform_artifacts(stable["version_name"], signed=True)
        output, _, macos, _ = directories
        (macos / "signature.txt").write_text("adhoc\n")
        with self.assertRaisesRegex(release.ReleaseError, "macOS signature"):
            release.assemble_distribution(*directories, stable, ios_signed=True)
        (macos / "signature.txt").write_text("developer-id-notarized\n")
        with self.assertRaisesRegex(release.ReleaseError, "signed iOS IPA"):
            release.assemble_distribution(*directories, stable, ios_signed=False)
        names = release.assemble_distribution(*directories, stable, ios_signed=True)
        self.assertIn("InkOTP-iOS.ipa", names)
        self.assertIn("Apple Ad Hoc", (output / "release-notes.md").read_text())
        release.verify_checksums(output, names)

    def test_public_bundle_cannot_contain_a_symlink_or_duplicate_checksum_entry(self):
        (self.root / "safe.apk").write_bytes(b"apk")
        release.write_checksums(self.root, ["safe.apk"])
        manifest = self.root / "SHA256SUMS"
        manifest.write_text(manifest.read_text() * 2)
        with self.assertRaises(release.ReleaseError):
            release.verify_checksums(self.root, ["safe.apk"])
        release.write_checksums(self.root, ["safe.apk"])
        (self.root / "safe.apk").unlink()
        (self.root / "safe.apk").symlink_to("SHA256SUMS")
        with self.assertRaises(release.ReleaseError):
            release.verify_checksums(self.root, ["safe.apk"])

    def test_preflight_rejects_existing_release_using_real_history_logic(self):
        version_source = 'versionCode = 3\nversionName = "0.4.0-beta.1"\n'
        def external(args, **kwargs):
            if args[:2] == ["gh", "api"]:
                return json.dumps([[{"tag_name": "v0.4.0-beta.1", "draft": True}]])
            if args[:3] == ["git", "tag", "--list"]:
                return "v0.4.0-beta.1\n"
            if args[:2] == ["git", "show"]:
                return version_source
            return "a" * 40 + "\n"
        with patch.object(release, "run", side_effect=external):
            with self.assertRaisesRegex(release.ReleaseError, "already exists"):
                release.preflight("v0.4.0-beta.1", release.config())

    def test_signing_failure_cleans_private_file_without_leaking_secrets(self):
        source = self.root / "unsigned"
        source.mkdir()
        (source / "app-release-unsigned.apk").write_bytes(b"APK input fixture")
        (source / "output-metadata.json").write_text(json.dumps({
            "applicationId": "io.github.rhczz.inkotp", "elements": [{
                "versionCode": 3, "versionName": "0.4.0-beta.1",
                "outputFile": "app-release-unsigned.apk", "filters": []}]}))
        temporary = self.root / "runner-temp"
        temporary.mkdir()
        key_path = temporary / "signing.jks"
        environment = {name: "do-not-log-secret" for name in release.SECRETS}
        environment[release.SECRETS[0]] = base64.b64encode(b"throwaway fixture bytes, not a key").decode()
        environment["RUNNER_TEMP"] = str(temporary)
        def external(args, **kwargs):
            if args[0].endswith("zipalign"):
                self.assertEqual(0o600, key_path.stat().st_mode & 0o777)
                Path(args[-1]).write_bytes(b"aligned fixture")
                return ""
            raise release.ReleaseError("apksigner failed; release stopped")
        stdout = io.StringIO()
        with patch.dict(os.environ, environment), patch.object(release, "inspect", return_value={}), \
                patch.object(release, "run", side_effect=external), contextlib.redirect_stdout(stdout):
            with self.assertRaisesRegex(release.ReleaseError, "apksigner failed"):
                release.sign("v0.4.0-beta.1", source, self.root / "bundle", Path("36.0.0"), key_path, release.config())
        self.assertFalse(key_path.exists())
        self.assertFalse((temporary / "aligned.apk").exists())
        self.assertNotIn("do-not-log-secret", stdout.getvalue())

    def test_subprocess_failure_never_includes_output_or_secret_arguments(self):
        import subprocess
        result = subprocess.CompletedProcess(["apksigner"], 1, stdout="sensitive output", stderr="sensitive error")
        with patch.object(release.subprocess, "run", return_value=result):
            with self.assertRaises(release.ReleaseError) as failure:
                release.run(["apksigner", "sign", "sensitive alias"])
        self.assertNotIn("sensitive", str(failure.exception))

    def test_publish_refuses_to_create_release_after_any_verification_failure(self):
        stderr = io.StringIO()
        with patch.object(release, "verify_bundle", side_effect=release.ReleaseError("certificate mismatch")), \
                patch.object(release, "run") as commands, contextlib.redirect_stderr(stderr):
            status = release.main(["publish", "--tag", "v0.4.0-beta.1", "--build-tools", "36.0.0"])
        self.assertEqual(1, status)
        commands.assert_not_called()
        self.assertIn("certificate mismatch", stderr.getvalue())

    def signing_fixture(self):
        source = self.root / "unsigned"
        source.mkdir()
        (source / "app-release-unsigned.apk").write_bytes(b"APK input fixture")
        (source / "output-metadata.json").write_text(json.dumps({
            "applicationId": "io.github.rhczz.inkotp", "elements": [{
                "versionCode": 3, "versionName": "0.4.0-beta.1",
                "outputFile": "app-release-unsigned.apk", "filters": []}]}))
        temporary = self.root / "runner-temp"
        temporary.mkdir()
        environment = {name: "throwaway test value" for name in release.SECRETS}
        environment[release.SECRETS[0]] = base64.b64encode(b"not a real key").decode()
        environment["RUNNER_TEMP"] = str(temporary)
        return source, temporary / "signing.jks", environment

    def test_signing_cancellation_removes_private_and_aligned_files(self):
        source, key_path, environment = self.signing_fixture()
        def external(args, **kwargs):
            if args[0].endswith("zipalign"):
                Path(args[-1]).write_bytes(b"aligned fixture")
                return ""
            raise KeyboardInterrupt()
        with patch.dict(os.environ, environment), patch.object(release, "inspect", return_value={}), \
                patch.object(release, "run", side_effect=external), self.assertRaises(KeyboardInterrupt):
            release.sign("v0.4.0-beta.1", source, self.root / "bundle", Path("36.0.0"), key_path, release.config())
        self.assertFalse(key_path.exists())
        self.assertFalse((key_path.parent / "aligned.apk").exists())

    def test_successful_signing_bundle_is_exact_verifiable_and_has_no_private_files(self):
        source, key_path, environment = self.signing_fixture()
        directory = self.root / "bundle"
        cfg = release.config()
        inspected = {"certificate_sha256": cfg["certificate_sha256"], "size_bytes": 22}
        context = {"commit": "a" * 40, "repository": cfg["repository"], "run_id": "7", "run_attempt": "1"}
        def external(args, **kwargs):
            if args[0].endswith("zipalign"):
                Path(args[-1]).write_bytes(b"aligned fixture")
            elif args[0].endswith("apksigner"):
                Path(args[args.index("--out") + 1]).write_bytes(b"signed fixture payload")
            return ""
        with patch.dict(os.environ, environment), patch.object(release, "inspect", return_value=inspected), \
                patch.object(release, "run", side_effect=external), patch.object(release, "build_context", return_value=context):
            release.sign("v0.4.0-beta.1", source, directory, Path("36.0.0"), key_path, cfg)
            self.assertEqual(self.version, release.verify_bundle("v0.4.0-beta.1", directory, Path("36.0.0"), cfg))
            context["run_attempt"] = "2"
            self.assertEqual(self.version, release.verify_bundle("v0.4.0-beta.1", directory, Path("36.0.0"), cfg))
            info = json.loads((directory / "build-info.json").read_text())
            self.assertEqual("a" * 40, info["commit"])
            self.assertEqual(cfg["certificate_sha256"], info["apk"]["certificate_sha256"])
            self.assertEqual(set(release.payload_names(self.version) + ["SHA256SUMS"]),
                             {path.name for path in directory.iterdir()})
            self.assertFalse(key_path.exists())
            # A self-consistent checksum manifest must not hide false build metadata.
            info["commit"] = "b" * 40
            (directory / "build-info.json").write_text(json.dumps(info))
            release.write_checksums(directory, release.payload_names(self.version))
            with self.assertRaisesRegex(release.ReleaseError, "source/run/signed APK"):
                release.verify_bundle("v0.4.0-beta.1", directory, Path("36.0.0"), cfg)

    def test_history_race_check_stops_publish_before_gh_create(self):
        stderr = io.StringIO()
        with patch.object(release, "verify_bundle", return_value=self.version), \
                patch.object(release, "preflight", side_effect=release.ReleaseError("release already exists")), \
                patch.object(release, "assemble_distribution", return_value=[]), \
                patch.object(release, "run") as commands, contextlib.redirect_stderr(stderr):
            status = release.main(["publish", "--tag", "v0.4.0-beta.1", "--build-tools", "36.0.0",
                                   "--macos-dir", "macos", "--ios-dir", "ios", "--ios-signing", "unsigned"])
        self.assertEqual(1, status)
        commands.assert_not_called()

    def test_unsigned_gate_rejects_an_apk_v2_signature_block(self):
        import struct
        apk = self.root / "unsigned.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"fixture")
        data = bytearray(apk.read_bytes())
        eocd = data.rfind(b"PK\x05\x06")
        central = struct.unpack_from("<I", data, eocd + 16)[0]
        block = b"\0" * 16 + b"APK Sig Block 42"
        data[central:central] = block
        struct.pack_into("<I", data, eocd + len(block) + 16, central + len(block))
        apk.write_bytes(data)
        with self.assertRaisesRegex(release.ReleaseError, "v2/v3"):
            release.reject_signature_blocks(apk)


if __name__ == "__main__":
    unittest.main()
