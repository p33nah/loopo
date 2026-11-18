from collections import defaultdict
from pathlib import Path

from loopo_functiongemma.corpus import build_corpus, main, numeric_shape
from loopo_functiongemma.dataset import _read_jsonl, text_key

PROJECT = Path(__file__).resolve().parents[1]


def test_contrastive_keeps_frozen_splits_and_covers_every_amount(tmp_path):
    followup = PROJECT / "examples/rejection-followup.txt"
    holdouts = [PROJECT / f"reports/{stem}.txt" for stem in ("rejection-probe", "contrastive-holdout")]
    command = ["generate", str(tmp_path), "--profile", "contrastive", "--examples", str(followup)]
    for holdout in holdouts:
        command.extend(("--holdout", str(holdout)))

    assert main(command) == 0
    train_file = tmp_path / "train.jsonl"
    previous = train_file.read_bytes()
    assert main(command) == 0
    assert train_file.read_bytes() == previous

    train = _read_jsonl(train_file)
    assert len(train) == 3000
    assert sum(not row["expected"] for row in train) == 1200
    for name in ("test", "challenge"):
        assert (tmp_path / f"{name}.jsonl").read_bytes() == (PROJECT / "data" / f"{name}.jsonl").read_bytes()
    assert _read_jsonl(tmp_path / "validation.jsonl") == build_corpus(examples=[followup])["validation"]

    amounts = defaultdict(set)
    shared_units = defaultdict(set)
    unitless_targets = defaultdict(set)
    for row in train:
        if row["source"] != "contrastive_numeric" or not row["expected"]:
            continue
        call = row["expected"][0]
        n = next(iter(call["arguments"].values()))
        form = row["variant"]
        final_word = text_key(row["text"]).split()[-1]
        amounts[call["name"], form].add(n)
        if final_word == "percento":
            shared_units[call["name"], form].add(abs(n))
        if call["name"] == "set_rate" and final_word == str(n):
            unitless_targets[form].add(n)
    for form in ("number_words", "number_digits"):
        assert amounts["set_rate", form] == set(range(25, 201))
        assert amounts["adjust_rate", form] == set(range(-175, 176)) - {0}
        neighbors = {69, 70, 71}
        assert neighbors <= unitless_targets[form]
        for name in ("set_rate", "adjust_rate"):
            assert neighbors <= shared_units[name, form]

    by_text = {text_key(row["text"]): row for row in train}
    assert by_text["non avviare ancora la traccia"]["expected"] == []
    assert by_text["continua a tenerla in pausa"]["expected"] == [{"name": "stay_paused", "arguments": {}}]
    assert sum(row["source"] == "generated_context" for row in train) < 1000

    train_shapes = {numeric_shape(row["text"]) for row in train} - {None}
    regression = _read_jsonl(tmp_path / "numeric-replay.jsonl")
    rejection = _read_jsonl(tmp_path / "rejection-probe.jsonl")
    fresh = _read_jsonl(tmp_path / "contrastive-holdout.jsonl")
    assert (len(regression), len(rejection)) == (26, 48)
    assert fresh
    for row in regression + rejection + fresh:
        assert text_key(row["text"]) not in by_text
        assert numeric_shape(row["text"]) not in train_shapes


def test_contrastive_rejects_contributed_numeric_template_overlap(tmp_path, capsys):
    evaluation = tmp_path / "new-eval.txt"
    training = tmp_path / "contribution.txt"
    evaluation.write_text("set_rate:91 | evaluation | Con calma regola la traccia al 91 percento.\n", encoding="utf-8")
    training.write_text("set_rate:92 | contribution | Con calma regola la traccia al novantadue percento.\n", encoding="utf-8")
    target = tmp_path / "data"

    assert main(["generate", str(target), "--profile", "contrastive", "--examples", str(training),
                 "--holdout", str(evaluation)]) == 2
    assert "contributed example overlaps evaluation" in capsys.readouterr().err
    assert not target.exists()
