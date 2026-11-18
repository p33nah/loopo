"""Publish and verify a portable copy of a finished FunctionGemma run."""

import argparse
import hashlib
import json
import os
import stat
import tempfile
from contextlib import contextmanager
from pathlib import Path, PurePosixPath
from zipfile import ZIP_DEFLATED, BadZipFile, ZipFile


MANIFEST = "artifact-manifest.json"
CHUNK_BYTES = 1024 * 1024


def chunks(stream):
    while block := stream.read(CHUNK_BYTES):
        yield block


def sha256_file(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_member(name: str) -> None:
    path = PurePosixPath(name)
    if not path.parts or path.is_absolute() or path.as_posix() != name or "\\" in name or ":" in name:
        raise ValueError(f"invalid archive path: {name!r}")
    for part in path.parts:
        if (part.startswith(".") or part.casefold() in {"credentials.json", "token.txt", "hf_token", "secrets.json"}
                or part.casefold().endswith((".env", ".pem", ".key", ".tmp", ".part"))):
            raise ValueError(f"archive path looks private or incomplete: {name!r}")


def files_in(run: Path) -> list[tuple[str, Path]]:
    members = []
    for source in sorted(run.rglob("*")):
        name = source.relative_to(run).as_posix()
        validate_member(name)
        if source.is_symlink():
            raise ValueError(f"archive does not follow a symbolic link: {name!r}")
        if source.is_dir():
            continue
        if not source.is_file() or name == MANIFEST:
            raise ValueError(f"unexpected run artifact: {name!r}")
        members.append((name, source))
    if not members:
        raise ValueError("run directory contains no files")
    return members


@contextmanager
def adjacent_temporary_file(destination: Path):
    descriptor, filename = tempfile.mkstemp(prefix=f".{destination.name}.", suffix=".tmp", dir=destination.parent)
    os.close(descriptor)
    temporary = Path(filename)
    try:
        yield temporary
    finally:
        temporary.unlink(missing_ok=True)


def record(digest, length: int) -> dict:
    return {"bytes": length, "sha256": digest.hexdigest()}


def verify_payload(archive_path: Path) -> int:
    with ZipFile(archive_path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or MANIFEST not in names:
            raise ValueError("archive must contain one manifest and unique paths")
        for member in archive.infolist():
            validate_member(member.filename)
            if member.is_dir() or stat.S_ISLNK(member.external_attr >> 16):
                raise ValueError(f"unexpected archive entry: {member.filename!r}")
        manifest = json.loads(archive.read(MANIFEST))
        if not isinstance(manifest, dict) or manifest.get("format") != 1:
            raise ValueError("unsupported artifact manifest format")
        entries = manifest.get("files")
        if not isinstance(entries, dict) or not entries or set(entries) != set(names) - {MANIFEST}:
            raise ValueError("manifest does not match archive entries")
        for name, expected in entries.items():
            digest = hashlib.sha256()
            length = 0
            with archive.open(name) as stream:
                for block in chunks(stream):
                    digest.update(block)
                    length += len(block)
            if record(digest, length) != expected:
                raise ValueError(f"checksum or size mismatch: {name!r}")
    return len(entries)


def archive_run(run: Path, archive_path: Path) -> tuple[int, str]:
    run = run.resolve(strict=True)
    archive_path = archive_path.resolve()
    if not run.is_dir() or archive_path.suffix != ".zip" or archive_path.is_relative_to(run):
        raise ValueError("choose a run directory and a .zip destination outside it")
    members = files_in(run)
    archive_path.parent.mkdir(parents=True, exist_ok=True)
    checksum = archive_path.with_suffix(".zip.sha256")
    with adjacent_temporary_file(archive_path) as packed, adjacent_temporary_file(checksum) as pending_checksum:
        with ZipFile(packed, "w", compression=ZIP_DEFLATED, compresslevel=1) as archive:
            entries = {}
            for name, source in members:
                digest = hashlib.sha256()
                length = 0
                with source.open("rb") as stream, archive.open(name, "w", force_zip64=True) as destination:
                    for block in chunks(stream):
                        digest.update(block)
                        length += len(block)
                        destination.write(block)
                entries[name] = record(digest, length)
            archive.writestr(MANIFEST, json.dumps({"format": 1, "files": entries}, indent=2) + "\n")
        count = verify_payload(packed)
        digest = sha256_file(packed)
        pending_checksum.write_text(f"{digest}  {archive_path.name}\n", encoding="utf-8")
        packed.replace(archive_path)
        pending_checksum.replace(checksum)
    return count, digest


def verify_archive(archive_path: Path) -> tuple[int, str]:
    count = verify_payload(archive_path)
    digest = sha256_file(archive_path)
    checksum = archive_path.with_suffix(".zip.sha256")
    if checksum.exists() and checksum.read_text(encoding="utf-8").strip() != f"{digest}  {archive_path.name}":
        raise ValueError("archive checksum does not match the sidecar")
    return count, digest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    publish = commands.add_parser("archive", help="write a verified zip and a SHA-256 sidecar")
    publish.add_argument("run", type=Path)
    publish.add_argument("zip", type=Path)
    verify = commands.add_parser("verify", help="check all archived entries and the sidecar when present")
    verify.add_argument("zip", type=Path)
    args = parser.parse_args()
    try:
        count, digest = archive_run(args.run, args.zip) if args.command == "archive" else verify_archive(args.zip)
    except (BadZipFile, OSError, ValueError) as error:
        parser.exit(1, f"artifact error: {error}\n")
    print(f"{args.zip}: verified {count} files, SHA-256 {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
