"""Verify the actual consumer artifact, independently of Gradle configuration."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import struct
import zipfile


def dex_contains_string(data, value):
    if len(data) < 112 or not data.startswith(b"dex\n"):
        return False
    count, offset = struct.unpack_from("<II", data, 56)
    if offset < 112 or offset + count * 4 > len(data):
        raise ValueError("Invalid DEX string table")
    expected = value.encode("utf-8") + b"\0"
    for index in range(count):
        position = struct.unpack_from("<I", data, offset + index * 4)[0]
        for _ in range(5):
            if position >= len(data):
                raise ValueError("Invalid DEX string")
            byte = data[position]
            position += 1
            if byte < 128:
                break
        else:
            raise ValueError("Invalid DEX length")
        if data[position:position + len(expected)] == expected:
            return True
    return False


def check_payload(apk, backend_url, secrets):
    if not backend_url.startswith("https://"):
        raise ValueError("Official HTTPS endpoint is missing")
    found = False
    with zipfile.ZipFile(apk) as archive:
        entries = archive.infolist()
        if sum(entry.file_size for entry in entries) > 512 * 1024 * 1024:
            raise ValueError("Unexpected artifact size")
        for entry in entries:
            if entry.is_dir():
                continue
            data = archive.read(entry)
            if re.fullmatch(r"classes\d*\.dex", entry.filename):
                found |= dex_contains_string(data, backend_url)
            if any(value and value.encode("utf-8") in data for value in secrets):
                raise ValueError("Private credential found in packaged artifact")
    if not found:
        raise ValueError("Official endpoint is absent from the compiled application")


def check_android(apk, build_tools):
    suffix = ".exe" if os.name == "nt" else ""
    badging = subprocess.run(
        [str(build_tools / ("aapt2" + suffix)), "dump", "badging", str(apk)],
        check=True, capture_output=True, text=True,
    ).stdout
    if "application-debuggable" in badging:
        raise ValueError("Debuggable artifact cannot be a consumer release")
    if "package: name='app.sense'" not in badging or "targetSdkVersion:'36'" not in badging:
        raise ValueError("Unexpected application ID or target SDK")
    signer = "apksigner.bat" if os.name == "nt" else "apksigner"
    verification = subprocess.run(
        [str(build_tools / signer), "verify", "--verbose", "--print-certs", str(apk)],
        check=True, capture_output=True, text=True,
    ).stdout
    if "CN=Android Debug" in verification:
        raise ValueError("Debug signing is not release signing")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--config", type=Path, default=Path("config/production.properties"))
    parser.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()
    try:
        match = re.search(r"^backendUrl\s*=\s*(.+)$", args.config.read_text(), re.M)
        endpoint = match.group(1).strip().rstrip("/") if match else ""
        secret_names = ("LIVEKIT_API_KEY", "LIVEKIT_API_SECRET", "RADAR_SECRET", "OWNER_ACCOUNT", "SENSE_KEYSTORE_PASSWORD", "SENSE_KEY_PASSWORD", "SENSE_KEYSTORE_BASE64")
        check_payload(args.apk, endpoint, [os.environ.get(name, "") for name in secret_names])
        check_android(args.apk, args.build_tools)
        print(json.dumps({"releaseArtifactVerified": True, "officialEndpoint": endpoint}))
    except Exception:
        # Never include subprocess output, credential bytes or private paths.
        print("Release artifact verification failed: inspect endpoint, manifest, signing and secret exclusion.")
        raise SystemExit(1)
