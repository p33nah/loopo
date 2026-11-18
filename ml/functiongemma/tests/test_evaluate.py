import hashlib
import json

import pytest

from loopo_functiongemma.evaluate import compare, quality_gates, score, select_checkpoint, summarize


RESUME = {"name": "resume", "arguments": {}}
RATE = {"name": "set_rate", "arguments": {"percent": 70}}


def test_exact_ordered_plan_arguments_and_false_accepts(tmp_path):
    rows = [
        {"id": "compound", "expected": [RATE, RESUME], "category": "compound"},
        {"id": "correct", "expected": [RATE], "category": "set_rate"},
        {"id": "unsupported", "expected": [], "category": "unsupported"},
        {"id": "ambiguous", "expected": [], "category": "ambiguous"},
    ]
    for row in rows:
        row.update(text="example", variant="synthetic_asr")
    predictions = [
        {"id": "compound", "calls": [dict(RATE, arguments={"percent": 72}), RESUME],
         "invalid": False, "latency_ms": 4},
        {"id": "correct", "calls": [RATE], "invalid": False, "latency_ms": 3},
        {"id": "unsupported", "calls": [RESUME], "invalid": False, "latency_ms": 2},
        {"id": "ambiguous", "calls": [], "invalid": True, "latency_ms": 1},
    ]
    metrics = summarize(score(rows, predictions))
    assert metrics["ordered_tools_positive"]["count"] == 2
    assert metrics["exact_plan"]["count"] == metrics["arguments_given_correct_tools"]["count"] == 1
    assert metrics["compound_exact"]["count"] == 0
    assert metrics["false_accept_on_negatives"]["count"] == 1
    assert metrics["no_call_on_ambiguous"]["count"] == 0
    assert metrics["dispatch_risk"]["count"] == 2
    assert metrics["latency_ms"] == {"mean": 2.5, "p50": 2.5, "p95": 4}

    dataset = tmp_path / "test.jsonl"
    dataset.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
    input_path = tmp_path / "predictions.json"
    input_path.write_text(json.dumps({
        "metadata": {"dataset_sha256": hashlib.sha256(dataset.read_bytes()).hexdigest()},
        "predictions": predictions,
    }), encoding="utf-8")
    output = tmp_path / "report"
    assert compare(dataset, {"fixture": input_path}, output, 0.5, 0.5, 0.5,
                   gate_inputs={"fixture"}) is True
    assert {path.name for path in output.iterdir()} == {
        "report.json", "predictions.csv", "confusion.csv", "summary.md",
    }
    summary = (output / "summary.md").read_text(encoding="utf-8")
    assert "wrong_plan" in summary
    assert "fixture: PASS" in summary

    assert compare(dataset, {"fixture": input_path}, output, 0.75, 0.5, 0.5,
                   gate_inputs={"fixture"}) is False
    assert "fixture: FAIL" in (output / "summary.md").read_text(encoding="utf-8")

    dataset.write_text(dataset.read_text(encoding="utf-8").replace("example", "changed"), encoding="utf-8")
    with pytest.raises(ValueError, match="different input"):
        compare(dataset, {"fixture": input_path}, output)


def test_comparison_rejects_missing_or_duplicate_prediction_ids():
    row = {"id": "command", "expected": [RESUME]}
    prediction = {"id": "command", "calls": [RESUME], "invalid": False, "latency_ms": 0}
    for observed in ([], [prediction, prediction]):
        with pytest.raises(ValueError, match="same IDs"):
            score([row], observed)


def test_quality_gates_use_positive_and_negative_denominators():
    rows = [
        {"id": "hit", "expected": [RESUME], "category": "resume"},
        {"id": "miss", "expected": [RATE], "category": "set_rate"},
        {"id": "negative", "expected": [], "category": "ambiguous"},
    ]
    observed = [
        {"id": "hit", "calls": [RESUME], "invalid": False, "latency_ms": 1},
        {"id": "miss", "calls": [], "invalid": False, "latency_ms": 1},
        {"id": "negative", "calls": [], "invalid": False, "latency_ms": 1},
    ]
    metrics = summarize(score(rows, observed))
    passed = quality_gates(metrics, 0.5, 0.0, 0.0)
    failed = quality_gates(metrics, 0.75, 0.0, 0.0)
    assert passed["passed"] is True
    assert passed["checks"]["positive_exact_plan"]["observed"] == 0.5
    assert failed["passed"] is False
    assert failed["checks"]["false_accept_on_negatives"]["observed"] == 0


def test_quality_gate_thresholds_must_be_probabilities():
    with pytest.raises(ValueError, match="between 0 and 1"):
        quality_gates({}, 1.1, None, None)


def test_checkpoint_selection_prefers_fewer_unwanted_calls_when_cost_ties(tmp_path):
    rows = [{"id": "play", "expected": [RESUME]}, {"id": "rate", "expected": [RATE]},
            {"id": "negated", "expected": []}]
    dataset = tmp_path / "validation.jsonl"
    dataset.write_text("".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
    paths = {}
    for name, plans in {"checkpoint-375": [[RESUME], [RATE], [RESUME]],
                        "checkpoint-750": [[], [], []]}.items():
        predictions = [{"id": row["id"], "calls": plan, "invalid": False, "latency_ms": 0}
                       for row, plan in zip(rows, plans, strict=True)]
        path = tmp_path / (name + ".json")
        path.write_text(json.dumps({"metadata": {"adapter": name,
                                                 "dataset_sha256": hashlib.sha256(dataset.read_bytes()).hexdigest()},
                                    "predictions": predictions}), encoding="utf-8")
        paths[name] = path
    output = tmp_path / "choice.json"
    select_checkpoint(dataset, paths, output)
    result = json.loads(output.read_text(encoding="utf-8"))
    assert result["selected"] == "checkpoint-750"
    assert {candidate["cost"] for candidate in result["candidates"].values()} == {2}
