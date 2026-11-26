"""Independent fixtures for the built-APK test-provider boundary."""
from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path

import verify_storage_apk_boundary as gate


TEST_MANIFEST = f'''N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=1)
    E: application (line=2)
      E: provider (line=3)
        A: android:name(0x01010003)="{gate.PROVIDER}" (Raw: "{gate.PROVIDER}")
        A: android:authorities(0x01010018)="{gate.AUTHORITY}" (Raw: "{gate.AUTHORITY}")
        A: android:exported(0x01010010)=(type 0x12)0x0
        A: android:grantUriPermissions(0x0101001b)=(type 0x12)0x0
      E: activity (line=10)
'''
PRODUCTION_MANIFEST = '''  E: manifest (line=1)
    E: application (line=2)
      E: provider (line=3)
        A: android:name(0x01010003)="app.morsecode.ExistingProvider"
        A: android:authorities(0x01010018)="app.morsecode.existing"
'''


class StorageApkBoundaryTest(unittest.TestCase):
    def test_private_test_provider_and_existing_production_provider_pass(self) -> None:
        gate.validate_manifests(TEST_MANIFEST, PRODUCTION_MANIFEST)
        gate.validate_dex(b"Slice4sTestProvider Slice4sProviderControls", b"ordinary application dex")

    def test_exported_or_uri_granting_provider_is_rejected(self) -> None:
        for attribute in ("exported", "grantUriPermissions"):
            with self.subTest(attribute=attribute):
                before = f"android:{attribute}"
                lines = TEST_MANIFEST.splitlines()
                mutated = "\n".join(line.replace("0x0", "0xffffffff") if before in line else line for line in lines)
                with self.assertRaises(gate.BoundaryError):
                    gate.validate_manifests(mutated, PRODUCTION_MANIFEST)
                removed = "\n".join(line for line in lines if before not in line)
                with self.assertRaises(gate.BoundaryError):
                    gate.validate_manifests(removed, PRODUCTION_MANIFEST)

    def test_absent_duplicate_wrong_class_or_wrong_authority_is_rejected(self) -> None:
        variants = (
            PRODUCTION_MANIFEST,
            TEST_MANIFEST + TEST_MANIFEST,
            TEST_MANIFEST.replace(gate.PROVIDER, "wrong.Provider"),
            TEST_MANIFEST.replace(gate.AUTHORITY, "wrong.authority"),
        )
        for variant in variants:
            with self.subTest(variant=variant), self.assertRaises(gate.BoundaryError):
                gate.validate_manifests(variant, PRODUCTION_MANIFEST)

    def test_production_manifest_and_every_test_only_dex_marker_are_rejected(self) -> None:
        for marker in (gate.PROVIDER, gate.AUTHORITY):
            with self.subTest(marker=marker), self.assertRaises(gate.BoundaryError):
                gate.validate_manifests(TEST_MANIFEST, PRODUCTION_MANIFEST + marker)
        for marker in gate.FORBIDDEN_DEX:
            with self.subTest(marker=marker), self.assertRaises(gate.BoundaryError):
                gate.validate_dex(b"Slice4sTestProvider Slice4sProviderControls", b"ordinary " + marker)

    def test_missing_test_code_or_missing_dex_cannot_pass(self) -> None:
        for test_dex in (b"", b"Slice4sTestProvider", b"Slice4sProviderControls"):
            with self.subTest(test_dex=test_dex), self.assertRaises(gate.BoundaryError):
                gate.validate_dex(test_dex, b"ordinary")
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "test.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", "not dex")
            with self.assertRaisesRegex(gate.BoundaryError, "no dex"):
                gate.dex_bytes(apk)
            with zipfile.ZipFile(apk, "a") as archive:
                archive.writestr("classes.dex", "first")
                archive.writestr("classes10.dex", "later")
            self.assertIn(b"later", gate.dex_bytes(apk))


if __name__ == "__main__":
    unittest.main()
