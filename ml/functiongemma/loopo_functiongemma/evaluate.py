"""Run FunctionGemma inference or compare saved predictions on identical transcripts."""

import argparse
import csv
import hashlib
import json
import math
import statistics
from collections import Counter
from itertools import chain
from pathlib import Path

from .model import predictions
from .tools import validate_plan
from .train import read_rows


def write_json(path: Path, content: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(content, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def ordered_tools(plan: list[dict]) -> str:
    return " → ".join(call["name"] for call in plan) or "no call"


def score(rows: list[dict], observed: list[dict]) -> list[dict]:
    indexed = {prediction["id"]: prediction for prediction in observed}
    if len(rows) != len({row["id"] for row in rows}):
        raise ValueError("evaluation rows contain duplicate IDs")
    if len(indexed) != len(observed) or indexed.keys() != {row["id"] for row in rows}:
        raise ValueError("predictions must cover the same IDs exactly once")
    results = []
    for row in rows:
        expected = validate_plan(row["expected"])
        prediction = indexed[row["id"]]
        calls = validate_plan(prediction["calls"])
        invalid = prediction["invalid"]
        latency = prediction["latency_ms"]
        if type(invalid) is not bool or (invalid and calls):
            raise ValueError(f"{row['id']}: invalid predictions must be fail-closed")
        if type(latency) not in (int, float) or not math.isfinite(latency) or latency < 0:
            raise ValueError(f"{row['id']}: latency must be finite and nonnegative")
        exact = expected == calls and not invalid
        correct_tools = not invalid and ordered_tools(expected) == ordered_tools(calls)
        if invalid:
            outcome = "invalid"
        elif exact:
            outcome = "exact" if expected else "rejected"
        elif not expected:
            outcome = "false_accept"
        elif not calls:
            outcome = "missed"
        else:
            outcome = "wrong_plan"
        results.append({
            **row, "calls": calls, "invalid": invalid, "raw": prediction.get("raw"),
            "error": prediction.get("error"), "latency_ms": latency,
            "generation_ms": prediction.get("generation_ms"), "exact": exact,
            "correct_tools": correct_tools, "outcome": outcome,
            "dispatch_risk": bool(calls) and calls != expected,
            "expected_tools": ordered_tools(expected),
            "predicted_tools": "invalid" if invalid else ordered_tools(calls),
        })
    return results


def rate(items: list[dict], condition) -> dict:
    count = sum(condition(row) for row in items)
    return {"count": count, "total": len(items), "rate": count / len(items) if items else None}


def summarize(rows: list[dict]) -> dict:
    positive = [row for row in rows if row["expected"]]
    negative = [row for row in rows if not row["expected"]]
    slots = [row for row in positive if row["correct_tools"] and any(call["arguments"] for call in row["expected"])]
    compound = [row for row in positive if len(row["expected"]) > 1]
    ambiguous = [row for row in negative if row["category"] == "ambiguous"]
    unsupported = [row for row in negative if row["category"] == "unsupported"]
    generation = [row["generation_ms"] for row in rows if row["generation_ms"] is not None]
    return {
        "exact_plan": rate(rows, lambda row: row["exact"]),
        "ordered_tools_positive": rate(positive, lambda row: row["correct_tools"]),
        "arguments_given_correct_tools": rate(slots, lambda row: row["exact"]),
        "compound_exact": rate(compound, lambda row: row["exact"]),
        "invalid": rate(rows, lambda row: row["invalid"]),
        "no_call_on_unsupported": rate(unsupported, lambda row: row["exact"]),
        "no_call_on_ambiguous": rate(ambiguous, lambda row: row["exact"]),
        "false_accept_on_negatives": rate(negative, lambda row: bool(row["calls"])),
        "dispatch_risk": rate(rows, lambda row: row["dispatch_risk"]),
        "latency_ms": latency_summary([row["latency_ms"] for row in rows]),
        "generation_only_ms": latency_summary(generation) if generation else None,
        "outcomes": dict(Counter(row["outcome"] for row in rows)),
    }


def latency_summary(samples: list[float]) -> dict:
    durations = sorted(samples)
    return {
        "mean": statistics.fmean(durations),
        "p50": statistics.median(durations),
        "p95": durations[math.ceil(0.95 * len(durations)) - 1],
    }


def metrics_by(rows: list[dict], field: str) -> dict:
    return {
        label: summarize([row for row in rows if row[field] == label])
        for label in sorted({row[field] for row in rows})
    }


def per_tool(rows: list[dict]) -> dict:
    expected = [set(call["name"] for call in row["expected"]) for row in rows]
    observed = [set(call["name"] for call in row["calls"]) for row in rows]
    return {
        name: {
            "true_positive": (correct := sum(name in a and name in b for a, b in zip(expected, observed, strict=True))),
            "expected_count": (targets := sum(name in plan for plan in expected)),
            "predicted_count": (outputs := sum(name in plan for plan in observed)),
            "recall": correct / targets if targets else None,
            "precision": correct / outputs if outputs else None,
        }
        for name in sorted(set(chain.from_iterable(expected + observed)))
    }


def confusion(rows: list[dict]) -> list[dict]:
    counts = Counter((row["expected_tools"], row["predicted_tools"]) for row in rows)
    return [
        {"expected": expected, "predicted": predicted, "count": count}
        for (expected, predicted), count in sorted(counts.items())
    ]


def load_predictions(path: Path, dataset_hash: str) -> tuple[list[dict], dict]:
    if path.suffix == ".jsonl":
        metadata = json.loads(path.with_suffix(".metadata.json").read_text(encoding="utf-8"))
        rows = read_rows(path)
    else:
        payload = json.loads(path.read_text(encoding="utf-8"))
        metadata, rows = payload["metadata"], payload["predictions"]
    if metadata.get("dataset_sha256") != dataset_hash:
        raise ValueError(f"{path}: predictions used a different input file")
    return rows, metadata


def write_csv(path: Path, rows: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="") as csv_file:
        writer = csv.DictWriter(csv_file, fieldnames=rows[0].keys())
        writer.writeheader()
        for row in rows:
            writer.writerow({
                key: json.dumps(value, ensure_ascii=False) if isinstance(value, (dict, list)) else value
                for key, value in row.items()
            })


def compare(dataset: Path, inputs: dict[str, Path], output: Path) -> None:
    rows = read_rows(dataset)
    if not rows:
        raise ValueError("evaluation dataset is empty")
    digest = hashlib.sha256(dataset.read_bytes()).hexdigest()
    systems = {}
    examples = []
    confusion_rows = []
    lines = [
        "# FunctionGemma transcript replay", "",
        f"Input SHA-256 `{digest}`", "",
        "| Interpreter | Exact | Ordered tools | Arguments | Compound | Invalid | False accepts | Mean ms | p50 ms | p95 ms |",
        "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for name, path in inputs.items():
        observed, metadata = load_predictions(path, digest)
        scored = score(rows, observed)
        summary = summarize(scored)
        matrix = confusion(scored)
        systems[name] = {
            "metadata": metadata, "metrics": summary, "by_category": metrics_by(scored, "category"),
            "by_variant": metrics_by(scored, "variant"), "per_tool": per_tool(scored), "confusion": matrix,
        }
        examples.extend({"interpreter": name, **row} for row in scored)
        confusion_rows.extend({"interpreter": name, **cell} for cell in matrix)
        keys = ("exact_plan", "ordered_tools_positive", "arguments_given_correct_tools",
                "compound_exact", "invalid", "false_accept_on_negatives")
        counts = [f"{summary[key]['count']}/{summary[key]['total']}" for key in keys]
        times = [f"{latency:.3f}" for latency in summary["latency_ms"].values()]
        lines.append("| " + " | ".join([name, *counts, *times]) + " |")

    lines.extend(["", "## Errors by category", ""])
    for name, system in systems.items():
        detail = ", ".join(
            f"{key}: {value['exact_plan']['count']}/{value['exact_plan']['total']} exact"
            for key, value in system["by_category"].items()
        )
        lines.append(f"- **{name}** — {detail}")
    lines.extend(["", "## Example failures", ""])
    for name in systems:
        failures = [row for row in examples if row["interpreter"] == name and not row["exact"]]
        for row in sorted(failures, key=lambda row: (not row["dispatch_risk"], row["id"]))[:5]:
            lines.append(f"- **{name}**, {row['id']}, {row['text']!r}. Expected "
                         f"`{row['expected']}`. Got `{row['calls']}` ({row['outcome']}).")
    output.mkdir(parents=True, exist_ok=True)
    write_json(output / "report.json", {"dataset_sha256": digest, "systems": systems, "examples": examples})
    write_csv(output / "predictions.csv", examples)
    write_csv(output / "confusion.csv", confusion_rows)
    (output / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def select_checkpoint(dataset: Path, inputs: dict[str, Path], output: Path) -> None:
    rows = read_rows(dataset)
    digest = hashlib.sha256(dataset.read_bytes()).hexdigest()
    if not rows or not any(row["expected"] for row in rows) or all(row["expected"] for row in rows):
        raise ValueError("selection requires both positive and negative validation examples")
    candidates = {}
    for name, path in inputs.items():
        observed, metadata = load_predictions(path, digest)
        checkpoint = Path(metadata["adapter"])
        if name != checkpoint.name or not name.startswith("checkpoint-"):
            raise ValueError(f"{path}: expected predictions from {name}")
        results = score(rows, observed)
        errors = sum(not row["exact"] for row in results)
        risky = sum(row["dispatch_risk"] for row in results)
        candidates[name] = {"errors": errors, "wrong_or_unwanted": risky, "cost": errors + risky}
    selected = min(candidates, key=lambda name: (
        candidates[name]["cost"], candidates[name]["wrong_or_unwanted"], int(name.removeprefix("checkpoint-")),
    ))
    write_json(output, {"dataset_sha256": digest, "selected": selected, "candidates": candidates})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    run = commands.add_parser("predict")
    run.add_argument("dataset", type=Path)
    run.add_argument("output", type=Path)
    model = run.add_mutually_exclusive_group()
    model.add_argument("--adapter", type=Path)
    model.add_argument("--merged", type=Path)
    report = commands.add_parser("compare")
    report.add_argument("dataset", type=Path)
    report.add_argument("output", type=Path)
    report.add_argument("--input", metavar="NAME=PATH", action="append", required=True)
    selection = commands.add_parser("select", help="rank saved checkpoint predictions on validation only")
    selection.add_argument("dataset", type=Path)
    selection.add_argument("output", type=Path)
    selection.add_argument("--input", metavar="NAME=PATH", action="append", required=True)
    args = parser.parse_args()
    if args.command == "predict":
        rows = read_rows(args.dataset)
        if not rows:
            parser.error("input must be nonempty")
        outputs, metadata = predictions(rows, args.adapter, merged=args.merged)
        metadata["dataset_sha256"] = hashlib.sha256(args.dataset.read_bytes()).hexdigest()
        write_json(args.output, {"metadata": metadata, "predictions": outputs})
    else:
        inputs = {}
        for value in args.input:
            name, _, path = value.partition("=")
            if not name or not path or name in inputs:
                parser.error("use --input NAME=PATH with unique nonempty names")
            inputs[name] = Path(path)
        operation = select_checkpoint if args.command == "select" else compare
        operation(args.dataset, inputs, args.output)


if __name__ == "__main__":
    main()
