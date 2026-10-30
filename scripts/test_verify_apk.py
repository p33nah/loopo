import contextlib
import io
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from verify_apk import verify


def elf(abi, *, machine=None, alignment=None):
    is_64 = abi != "armeabi-v7a"
    machine = machine if machine is not None else {"armeabi-v7a": 40, "arm64-v8a": 183, "x86_64": 62}[abi]
    header = bytearray(64 if is_64 else 52)
    header[:7] = b"\x7fELF" + bytes([2 if is_64 else 1, 1, 1])
    struct.pack_into("<H", header, 18, machine)
    if is_64:
        struct.pack_into("<Q", header, 32, 64)
        struct.pack_into("<HH", header, 54, 56, 1)
        program = struct.pack("<IIQQQQQQ", 1, 5, 0, 0, 0, 120, 120, alignment or 16384)
    else:
        struct.pack_into("<I", header, 28, 52)
        struct.pack_into("<HH", header, 42, 32, 1)
        program = struct.pack("<IIIIIIII", 1, 0, 0, 0, 84, 84, 5, alignment or 4096)
    return header + program


class VerifyApkTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "test.apk"

    def package(self, *, wrong_machine=False, unaligned=False, missing_abi=False, missing_notice=False):
        with zipfile.ZipFile(self.apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for abi in ("armeabi-v7a", "arm64-v8a", "x86_64"):
                if missing_abi and abi == "x86_64":
                    continue
                data = elf(abi, machine=62 if wrong_machine and abi == "arm64-v8a" else None,
                           alignment=4096 if unaligned and abi == "arm64-v8a" else None)
                archive.writestr(f"lib/{abi}/libloopo_native.so", data)
            for notice in ("Signalsmith-Stretch.txt", "Signalsmith-Linear.txt", "Loopo-MIT.txt", "Oboe-Apache-2.0.txt"):
                if not missing_notice or notice != "Loopo-MIT.txt":
                    archive.writestr(f"assets/third_party_licenses/{notice}", "license fixture")

    def check(self):
        with contextlib.redirect_stdout(io.StringIO()):
            verify(self.apk)

    def test_supported_machines_and_alignments_pass(self):
        self.package()
        self.check()

    def test_wrong_machine_in_correct_64_bit_directory_fails(self):
        self.package(wrong_machine=True)
        with self.assertRaisesRegex(AssertionError, "Wrong ELF machine for arm64-v8a"):
            self.check()

    def test_4_kb_64_bit_library_fails(self):
        self.package(unaligned=True)
        with self.assertRaisesRegex(AssertionError, "ELF alignment 4096"):
            self.check()

    def test_missing_abi_fails(self):
        self.package(missing_abi=True)
        with self.assertRaisesRegex(AssertionError, "Unexpected ABI set"):
            self.check()

    def test_missing_project_license_fails(self):
        self.package(missing_notice=True)
        with self.assertRaisesRegex(AssertionError, "Missing license: Loopo-MIT"):
            self.check()


if __name__ == "__main__":
    unittest.main()
