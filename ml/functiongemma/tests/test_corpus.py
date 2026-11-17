import json
import re
from pathlib import Path

import pytest

from loopo_functiongemma.corpus import build_corpus, main
from loopo_functiongemma.dataset import generate_dataset, validate_dataset


def test_balanced_cli_generates_all_splits_and_preserves_frozen_benchmark(tmp_path):
    assert main(["generate", str(tmp_path)]) == 0
    assert validate_dataset(tmp_path)["train"] == 3000
    train = [json.loads(line) for line in (tmp_path / "train.jsonl").read_text(encoding="utf-8").splitlines()]
    assert sum(not row["expected"] for row in train) == 1200
    assert sum(row["source"] == "curated_balanced" for row in train) >= 250
    assert {row["category"] for row in train if not row["expected"]} >= {"ambiguous", "negated", "unsupported"}
    for name in ("resume", "restart", "stay_paused"):
        assert sum(row["category"] == name for row in train) >= 150

    data = Path(__file__).resolve().parents[1] / "data"
    for name in ("test.jsonl", "challenge.jsonl"):
        assert (tmp_path / name).read_bytes() == (data / name).read_bytes()
    validation = [json.loads(line) for line in (tmp_path / "validation.jsonl").read_text(encoding="utf-8").splitlines()]
    original = generate_dataset()["validation"]
    assert validation[:len(original)] == original
    assert sum(not row["expected"] for row in validation) >= 30
    assert main(["inspect", str(tmp_path)]) == 0


def test_balanced_generation_is_deterministic_and_keeps_contributions(tmp_path):
    examples = tmp_path / "mie-frasi.txt"
    examples.write_text(
        "adjust_rate:-8+restart | prova_coro | Leva otto punti e rifai il pezzo dalla battuta iniziale.\n"
        "reject:negated | prova_coro | Per questa prova evita proprio di rimettere il brano da capo.\n",
        encoding="utf-8",
    )
    first = build_corpus(2000, 35, [examples])
    assert first == build_corpus(2000, 35, [examples])
    contributed = [row for row in first["train"] if row["source"] == "contributed"]
    assert len(contributed) == 2
    assert len({row["family"] for row in contributed}) == 1
    assert contributed[0]["expected"] == [
        {"name": "adjust_rate", "arguments": {"delta_percent": -8}},
        {"name": "restart", "arguments": {}},
    ]
    assert not contributed[1]["expected"]
    assert sum(not row["expected"] for row in first["train"]) == 700


def test_import_rejects_frozen_transcripts_and_numeric_template_variants(tmp_path, capsys):
    row = next(r for r in generate_dataset()["test"] if re.search(r"\d+", r["text"]) and r["expected"])
    examples = tmp_path / "mie-frasi.txt"
    output = tmp_path / "output"
    for phrase, error in [(row["text"].upper(), "contradictory labels"),
                          (re.sub(r"\d+", "62", row["text"]), "numeric template")]:
        examples.write_text(f"reject:ambiguous | prova | {phrase}\n", encoding="utf-8")
        assert main(["generate", str(output), "--examples", str(examples)]) == 2
        assert error in capsys.readouterr().err
        assert not output.exists()


def test_personal_import_contains_only_contributed_training_rows(tmp_path):
    sample = Path(__file__).resolve().parents[1] / "examples" / "le-mie-frasi.txt"
    assert main(["import", str(tmp_path), "--examples", str(sample)]) == 0
    assert validate_dataset(tmp_path) == {"train": 5, "validation": 66, "test": 89, "challenge": 32}
    train = [json.loads(line) for line in (tmp_path / "train.jsonl").read_text(encoding="utf-8").splitlines()]
    assert {row["source"] for row in train} == {"contributed"}


@pytest.mark.parametrize("bad_label", ["set_rate:210", "reject:unknown", "resume+restart", "adjust_rate:0"])
def test_import_exposes_bad_labels(tmp_path, capsys, bad_label):
    examples = tmp_path / "mie-frasi.txt"
    examples.write_text(f"{bad_label} | prova | Regola qui questa traccia.\n", encoding="utf-8")
    assert main(["generate", str(tmp_path / "out"), "--examples", str(examples)]) == 2
    assert "mie-frasi.txt:1:" in capsys.readouterr().err
