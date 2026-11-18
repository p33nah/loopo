import json
from zipfile import ZipFile

import pytest

from loopo_functiongemma.artifacts import MANIFEST, archive_run, verify_archive


def test_archive_contains_every_run_artifact_and_verifies_after_reopening(tmp_path):
    run = tmp_path / "drive" / "runs" / "trial"
    parts = {
        "data/train.jsonl": '{"text":"riprendi a studiare"}\n'.encode(),
        "training/checkpoint-125/optimizer.pt": b"fake checkpoint\0",
        "training/adapter/adapter_model.safetensors": b"fake adapter\0",
        "merged-hf/model.safetensors": b"fake merged model\0",
        "android/model.litertlm": b"fake bundle\0",
        "challenge-report/summary.md": "Confronto velocità\n".encode(),
    }
    for name, content in parts.items():
        path = run / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)

    destination = run.parent / "trial.zip"
    count, digest = archive_run(run, destination)
    assert (count, digest) == verify_archive(destination)
    assert count == len(parts)
    with ZipFile(destination) as archive:
        assert set(archive.namelist()) == {*parts, MANIFEST}
        assert {name: archive.read(name) for name in parts} == parts

    checksum = destination.with_suffix(".zip.sha256")
    checksum.write_text("0" * 64 + "  trial.zip\n", encoding="utf-8")
    with pytest.raises(ValueError, match="does not match the sidecar"):
        verify_archive(destination)


def test_verification_rejects_replaced_content_even_without_the_sidecar(tmp_path):
    run = tmp_path / "run"
    run.mkdir()
    (run / "run.json").write_text('{"steps": 9}', encoding="utf-8")
    destination = tmp_path / "run.zip"
    archive_run(run, destination)
    with ZipFile(destination) as archive:
        manifest = json.loads(archive.read(MANIFEST))
    destination.with_suffix(".zip.sha256").unlink()
    with ZipFile(destination, "w") as archive:
        archive.writestr("run.json", '{"steps": 0}')
        archive.writestr(MANIFEST, json.dumps(manifest))
    with pytest.raises(ValueError, match="checksum or size mismatch"):
        verify_archive(destination)


def test_failed_retry_keeps_published_archive_and_refuses_secrets(tmp_path):
    run = tmp_path / "run"
    run.mkdir()
    (run / "run.json").write_text("{}", encoding="utf-8")
    destination = tmp_path / "run.zip"
    published = archive_run(run, destination)
    (run / ".env").write_text("HF_TOKEN=example", encoding="utf-8")
    with pytest.raises(ValueError, match="private or incomplete"):
        archive_run(run, destination)
    assert verify_archive(destination) == published
    assert not tuple(tmp_path.glob("*.tmp"))


def test_archive_destination_cannot_be_inside_the_run(tmp_path):
    (tmp_path / "run.json").write_text("{}", encoding="utf-8")
    with pytest.raises(ValueError, match="destination outside it"):
        archive_run(tmp_path, tmp_path / "run.zip")
