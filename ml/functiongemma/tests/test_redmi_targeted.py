import json
import tomllib
from collections import Counter
from pathlib import Path

import pytest

from loopo_functiongemma.corpus import main
from loopo_functiongemma.dataset import text_key, validate_dataset

PROJECT = Path(__file__).resolve().parents[1]
EXAMPLES = PROJECT / "examples/redmi-targeted.txt"


def test_redmi_candidate_is_small_targeted_and_keeps_frozen_splits(tmp_path):
    command = ["import", str(tmp_path), "--examples", str(EXAMPLES)]
    targeted_validation = PROJECT / "examples/redmi-targeted-validation.txt"
    command.extend(("--validation", str(targeted_validation)))
    prior_holdouts = (
        PROJECT / "loopo_functiongemma/examples/numeric-replay.txt",
        PROJECT / "reports/rejection-probe.txt",
        PROJECT / "reports/contrastive-holdout.txt",
        PROJECT / "reports/targeted-holdout.txt",
    )
    for path in prior_holdouts:
        command.extend(("--holdout", str(path)))

    assert main(command) == 0
    assert validate_dataset(tmp_path) == {"train": 46, "validation": 76, "test": 89, "challenge": 32}
    train = [json.loads(line) for line in (tmp_path / "train.jsonl").read_text(encoding="utf-8").splitlines()]
    assert {row["source"] for row in train} == {"contributed"}
    assert len(train) < 50
    labels = Counter(row["category"] for row in train)
    assert {"negated", "out_of_range", "ambiguous", "unsupported", "unrelated", "compound", "stay_paused"} <= set(labels)
    assert labels["set_rate"] >= 6 and labels["adjust_rate"] >= 6

    validation_rows = [json.loads(line) for line in (tmp_path / "validation.jsonl").read_text(encoding="utf-8").splitlines()]
    assert len(validation_rows) == 66 + 10
    assert {row["source"] for row in validation_rows[66:]} == {"contributed_validation"}
    evaluation = [
        json.loads(line)
        for name in ("validation", "test", "challenge")
        for line in (PROJECT / f"data/{name}.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    for path in prior_holdouts:
        evaluation.extend(
            {"text": line.split("|", 2)[2].strip()}
            for line in path.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        )
    protected = {text_key(row["text"]) for row in (*evaluation, *validation_rows)}
    assert not protected.intersection(text_key(row["text"]) for row in train)
    for split in ("test", "challenge"):
        assert (tmp_path / f"{split}.jsonl").read_bytes() == (PROJECT / f"data/{split}.jsonl").read_bytes()


@pytest.mark.parametrize(
    ("training", "heldout", "message"),
    [
        (
            "set_rate:92 | target | Regola la traccia al novantadue percento\n",
            "reject:ambiguous | held | Regola la traccia al novantadue percento!\n",
            "evaluation phrase",
        ),
        (
            "set_rate:92 | target | Regola la traccia al 92 percento\n",
            "set_rate:90 | held | Regola la traccia al 91 percento\n",
            "numeric template",
        ),
    ],
)
def test_import_blocks_new_holdout_phrase_and_numeric_templates(tmp_path, capsys, training, heldout, message):
    examples, holdout = tmp_path / "examples.txt", tmp_path / "holdout.txt"
    examples.write_text(training, encoding="utf-8")
    holdout.write_text(heldout, encoding="utf-8")
    assert main(["import", str(tmp_path / "dataset"), "--examples", str(examples),
                 "--holdout", str(holdout)]) == 2
    assert message in capsys.readouterr().err


def test_import_blocks_targeted_validation_from_new_holdout(tmp_path, capsys):
    validation, holdout = tmp_path / "validation.txt", tmp_path / "holdout.txt"
    validation.write_text("set_rate:91 | validation | Regola la traccia al novantuno percento\n", encoding="utf-8")
    holdout.write_text("set_rate:90 | held | Regola la traccia al novantuno percento!\n", encoding="utf-8")
    assert main(["import", str(tmp_path / "dataset"), "--examples", str(EXAMPLES),
                 "--validation", str(validation), "--holdout", str(holdout)]) == 2
    assert "validation example overlaps a holdout phrase" in capsys.readouterr().err


def test_redmi_candidate_recipe_is_small_and_reproducible():
    config = tomllib.loads((PROJECT / "configs/redmi-targeted.toml").read_text(encoding="utf-8"))
    assert config["epochs"] == 8
    assert config["learning_rate"] == 0.00005
    assert config["train_limit"] == config["validation_limit"] == 0
    assert config["checkpoint_steps"] == 20
    assert config["retain_all_checkpoints"] is True
