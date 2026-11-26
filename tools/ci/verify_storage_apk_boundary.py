#!/usr/bin/env python3
"""Verify the built test provider manifest and reject its production APK leakage.

This checks the actual binary manifests with the Android SDK's aapt and inspects
all dex entries in both APKs. It complements, rather than replaces, source-set
and dependency fingerprints and the API-23 same-UID instrumentation check.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROVIDER = "app.morsecode.core.storage.transfer.Slice4sTestProvider"
AUTHORITY = "app.morsecode.core.storage.test.slice4s.provider"
FORBIDDEN_DEX = (
    b"Slice4sTestProvider",
    b"Slice4sProviderControls",
    AUTHORITY.encode(),
    b"SingleFileTypedReaderRuntimeTest",
    b"TestTypedSource",
    b"TestApprovalInteraction",
)


class BoundaryError(Exception):
    """A built artifact cannot establish the test-only boundary."""


def provider_blocks(manifest: str) -> list[str]:
    lines = manifest.splitlines()
    blocks: list[str] = []
    for i, line in enumerate(lines):
        match = re.match(r"^(\s*)E:\s+provider(?:\s|$)", line)
        if not match:
            continue
        depth = len(match.group(1))
        block = [line]
        for following in lines[i + 1 :]:
            element = re.match(r"^(\s*)E:\s+", following)
            if element and len(element.group(1)) <= depth:
                break
            block.append(following)
        blocks.append("\n".join(block))
    return blocks


def string_attribute(block: str, name: str) -> str | None:
    match = re.search(rf'\bA:\s+android:{re.escape(name)}(?:\(0x[0-9a-fA-F]+\))?="([^"]*)"', block)
    return match.group(1) if match else None


def false_attribute(block: str, name: str) -> bool:
    return bool(re.search(
        rf"\bA:\s+android:{re.escape(name)}(?:\(0x[0-9a-fA-F]+\))?=\(type 0x12\)0x0(?:\s|$)",
        block,
    ))


def validate_manifests(test_manifest: str, production_manifest: str) -> None:
    if PROVIDER in production_manifest or AUTHORITY in production_manifest:
        raise BoundaryError("test provider appears in the production APK manifest")
    matches = [
        block for block in provider_blocks(test_manifest)
        if string_attribute(block, "name") == PROVIDER or string_attribute(block, "authorities") == AUTHORITY
    ]
    if len(matches) != 1:
        raise BoundaryError("test APK must contain exactly one matching test provider")
    provider = matches[0]
    if string_attribute(provider, "name") != PROVIDER or string_attribute(provider, "authorities") != AUTHORITY:
        raise BoundaryError("test provider class or unique authority differs")
    if not false_attribute(provider, "exported") or not false_attribute(provider, "grantUriPermissions"):
        raise BoundaryError("test provider must explicitly deny export and URI grants")


def dex_bytes(apk: Path) -> bytes:
    try:
        with zipfile.ZipFile(apk) as archive:
            names = [name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
            if not names:
                raise BoundaryError(f"{apk}: APK contains no dex")
            return b"\n".join(archive.read(name) for name in names)
    except (OSError, zipfile.BadZipFile, KeyError) as exc:
        raise BoundaryError(f"{apk}: unreadable APK: {exc}") from exc


def validate_dex(test_dex: bytes, production_dex: bytes) -> None:
    if b"Slice4sTestProvider" not in test_dex or b"Slice4sProviderControls" not in test_dex:
        raise BoundaryError("test APK lacks the provider or its deterministic controls")
    leaked = [marker.decode() for marker in FORBIDDEN_DEX if marker in production_dex]
    if leaked:
        raise BoundaryError("test-only code or authority in production dex: " + ", ".join(leaked))


def only_apk(pattern: str) -> Path:
    candidates = sorted(ROOT.glob(pattern))
    if len(candidates) != 1:
        raise BoundaryError(f"expected exactly one APK at {pattern}, found {len(candidates)}")
    return candidates[0]


def aapt_manifest(aapt: str, apk: Path) -> str:
    try:
        result = subprocess.run(
            [aapt, "dump", "xmltree", str(apk), "AndroidManifest.xml"],
            check=True, capture_output=True, text=True,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise BoundaryError(f"aapt could not inspect {apk.name}: {exc}") from exc
    return result.stdout


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aapt", default=os.environ.get("AAPT", "aapt"))
    args = parser.parse_args()
    try:
        test_apk = only_apk("core-storage/build/outputs/apk/androidTest/debug/*.apk")
        app_apk = only_apk("app/build/outputs/apk/debug/*.apk")
        validate_manifests(aapt_manifest(args.aapt, test_apk), aapt_manifest(args.aapt, app_apk))
        validate_dex(dex_bytes(test_apk), dex_bytes(app_apk))
    except BoundaryError as exc:
        print(f"::error title=storage test APK boundary::{exc}")
        return 1
    print("::notice title=storage test APK boundary::test provider is private in the built test manifest; provider, fault controls and test reader/approval markers are absent from production dex and manifest")
    return 0


if __name__ == "__main__":
    sys.exit(main())
