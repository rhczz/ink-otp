import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("android_storage_check", ROOT / "tools/check_android_storage.py")
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class StorageContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        source = ROOT / "android/app/src/main"
        target = self.root / "android/app/src/main"
        target.mkdir(parents=True)
        shutil.copy2(source / "AndroidManifest.xml", target)
        shutil.copytree(source / "res/xml", target / "res/xml")
        self.manifest = target / "AndroidManifest.xml"
        self.xml = target / "res/xml"

    def test_current_contract_passes(self):
        self.assertEqual([], checker.validate(self.root))

    def test_enabling_backup_is_rejected(self):
        self.manifest.write_text(self.manifest.read_text().replace('android:allowBackup="false"', 'android:allowBackup="true"'))
        self.assertTrue(any("allowBackup" in error for error in checker.validate(self.root)))

    def test_retained_user_data_option_is_rejected(self):
        self.manifest.write_text(self.manifest.read_text().replace('android:hasFragileUserData="false"', 'android:hasFragileUserData="true"'))
        self.assertTrue(any("hasFragileUserData" in error for error in checker.validate(self.root)))

    def test_device_transfer_gap_is_rejected(self):
        file = self.xml / "data_extraction_rules.xml"
        file.write_text(file.read_text().replace('<exclude domain="device_file" path="." />', '', 1))
        self.assertTrue(any("device_file" in error for error in checker.validate(self.root)))

    def test_missing_device_transfer_section_is_rejected(self):
        file = self.xml / "data_extraction_rules.xml"
        file.write_text(file.read_text().replace("device-transfer", "unexpected-transfer"))
        self.assertTrue(any("缺少 device-transfer" in error for error in checker.validate(self.root)))

    def test_missing_or_malformed_rules_is_safe_failure(self):
        file = self.xml / "backup_rules.xml"
        file.write_text("<not-xml")
        self.assertTrue(checker.validate(self.root))
        file.unlink()
        self.assertTrue(checker.validate(self.root))

    def test_user_cannot_be_blocked_from_system_clear_data(self):
        self.manifest.write_text(self.manifest.read_text().replace('<application', '<application android:allowClearUserData="false"'))
        self.assertTrue(any("清除数据" in error for error in checker.validate(self.root)))
