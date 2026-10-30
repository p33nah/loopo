"""Check the native libraries in an APK without Android-specific Python packages."""

import argparse
import struct
import zipfile
from pathlib import Path


def verify(apk: Path) -> None:
    expected_abis = {"armeabi-v7a", "arm64-v8a", "x86_64"}
    with zipfile.ZipFile(apk) as archive, apk.open("rb") as raw:
        native = [entry for entry in archive.infolist() if entry.filename.endswith(".so")]
        assert native, "APK contains no native libraries"
        actual_abis = {entry.filename.split("/")[1] for entry in native}
        assert actual_abis == expected_abis, f"Unexpected ABI set: {actual_abis}"
        for abi in expected_abis:
            assert f"lib/{abi}/libloopo_native.so" in archive.namelist(), f"Missing engine for {abi}"
        for entry in native:
            data = archive.read(entry)
            assert data[:4] == b"\x7fELF", f"Invalid ELF: {entry.filename}"
            assert data[5] == 1, f"Unsupported byte order: {entry.filename}"
            is_64 = data[4] == 2
            assert data[4] in (1, 2), f"Invalid ELF class: {entry.filename}"
            abi = entry.filename.split("/")[1]
            assert is_64 == (abi != "armeabi-v7a"), f"ABI and ELF class disagree: {entry.filename}"
            required_alignment = 16384 if is_64 else 4096
            offset = struct.unpack_from("<Q" if is_64 else "<I", data, 32 if is_64 else 28)[0]
            stride, count = struct.unpack_from("<HH", data, 54 if is_64 else 42)
            loads = 0
            for index in range(count):
                base = offset + index * stride
                kind = struct.unpack_from("<I", data, base)[0]
                if kind != 1:
                    continue
                loads += 1
                if is_64:
                    _, _, file_offset, address, _, _, _, alignment = struct.unpack_from("<IIQQQQQQ", data, base)
                else:
                    _, file_offset, address, _, _, _, _, alignment = struct.unpack_from("<IIIIIIII", data, base)
                assert alignment >= required_alignment, f"ELF alignment {alignment}: {entry.filename}"
                assert (address - file_offset) % required_alignment == 0, f"ELF segment offset: {entry.filename}"
            assert loads, f"No loadable ELF segments: {entry.filename}"
            if entry.compress_type == zipfile.ZIP_STORED:
                raw.seek(entry.header_offset + 26)
                name_length, extra_length = struct.unpack("<HH", raw.read(4))
                data_offset = entry.header_offset + 30 + name_length + extra_length
                assert data_offset % 16384 == 0, f"ZIP alignment: {entry.filename}"
            print(f"Verified {required_alignment // 1024} KB ELF and packaging alignment: {entry.filename}")
        for notice in ("Signalsmith-Stretch.txt", "Signalsmith-Linear.txt", "Loopo-MIT.txt", "Oboe-Apache-2.0.txt"):
            assert f"assets/third_party_licenses/{notice}" in archive.namelist(), f"Missing license: {notice}"
    print(f"Verified {apk.name} for {', '.join(sorted(actual_abis))}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    verify(parser.parse_args().apk)
