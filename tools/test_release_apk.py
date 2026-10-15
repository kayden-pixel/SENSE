import tempfile
from pathlib import Path
import unittest
import zipfile
import struct

from check_release_apk import check_payload, dex_contains_string


def string_table_fixture(value):
    data = bytearray(116)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<II", data, 56, 1, 112)
    struct.pack_into("<I", data, 112, 116)
    return data + bytes([len(value)]) + value.encode() + b"\0"


class ReleasePayloadTests(unittest.TestCase):
    def test_official_url_must_exist_in_compiled_code(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / "fixture.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("assets/config.txt", "https://sense.onrender.com")
                archive.writestr("classes.dex", string_table_fixture("https://wrong.onrender.com"))
            with self.assertRaises(ValueError):
                check_payload(apk, "https://sense.onrender.com", [])

    def test_credentials_are_detected_inside_compressed_entries(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / "fixture.apk"
            with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("classes.dex", string_table_fixture("https://sense.onrender.com"))
                archive.writestr("assets/leak.txt", "private-test-only-api-secret")
            with self.assertRaises(ValueError):
                check_payload(apk, "https://sense.onrender.com", ["private-test-only-api-secret"])
            check_payload(apk, "https://sense.onrender.com", ["different-private-credential"])

    def test_url_prefix_and_embedded_substring_are_not_the_official_endpoint(self):
        endpoint = "https://sense.onrender.com"
        self.assertFalse(dex_contains_string(string_table_fixture(endpoint + ".other.org"), endpoint))
        self.assertFalse(dex_contains_string(string_table_fixture("wrong/" + endpoint), endpoint))
        self.assertTrue(dex_contains_string(string_table_fixture(endpoint), endpoint))


if __name__ == "__main__":
    unittest.main()
