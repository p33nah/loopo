"""Build and audit the frozen, independently authored transcript holdout."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT))

from loopo_functiongemma.corpus import parse_label
from loopo_functiongemma.dataset import text_key
from loopo_functiongemma.train import read_rows


SOURCE = PROJECT / "reports/targeted-holdout.txt"
OUTPUT = PROJECT / "reports/targeted-holdout.jsonl"


def read_source() -> list[dict]:
    rows = []
    for line_number, raw in enumerate(SOURCE.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        try:
            label, family, transcript = (part.strip() for part in line.split("|", 2))
            category, expected = parse_label(label)
            key = text_key(transcript)
            if not key:
                raise ValueError("transcript is empty after normalization")
            variant = "synthetic_asr" if family.endswith("_asr") else (
                "dialectal" if family.endswith("_dialect") else "clean"
            )
            row_id = hashlib.sha1(f"targeted-holdout\0{key}".encode("utf-8")).hexdigest()[:12]
            rows.append({
                "id": f"targeted-{row_id}", "text": transcript, "expected": expected,
                "category": category, "variant": variant,
                "family": f"targeted_{family}", "source": "independent_authored",
            })
        except (ValueError, TypeError) as error:
            raise ValueError(f"{SOURCE}:{line_number}: {error}") from error
    return rows


def collision_sources() -> list[Path]:
    fixed = [PROJECT / f"data/{name}.jsonl" for name in ("train", "validation", "test", "challenge")]
    fixed.extend(sorted((PROJECT / "reports").glob("*.txt")))
    return [path for path in fixed if path != SOURCE]


def audit(rows: list[dict]) -> None:
    seen: dict[str, str] = {}
    for row in rows:
        key = text_key(row["text"])
        if key in seen:
            raise ValueError(f"duplicate holdout transcripts: {seen[key]} and {row['id']}")
        seen[key] = row["id"]

    collisions = []
    for source in collision_sources():
        if source.suffix == ".jsonl":
            other_rows = read_rows(source)
            texts = ((row["id"], row["text"]) for row in other_rows)
        else:
            texts = ((f"{source.name}:{index}", line.partition("|")[-1].partition("|")[-1].strip())
                     for index, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1)
                     if line.strip() and not line.lstrip().startswith("#"))
        for other_id, transcript in texts:
            key = text_key(transcript)
            if key in seen:
                collisions.append(f"{row_id_for(seen[key])} collides with {source.relative_to(PROJECT)}:{other_id}")
    if collisions:
        raise ValueError("normalized transcript collisions:\n" + "\n".join(collisions[:20]))

    positives = sum(bool(row["expected"]) for row in rows)
    negatives = len(rows) - positives
    categories = {row["category"] for row in rows}
    tools = {call["name"] for row in rows for call in row["expected"]}
    required_tools = {"resume", "restart", "stay_paused", "set_rate", "adjust_rate"}
    if positives < 60 or negatives < 60:
        raise ValueError(f"holdout needs at least 60 positives and negatives; got {positives}/{negatives}")
    if not {"ambiguous", "negated", "unsupported"} <= categories:
        raise ValueError("holdout must cover ambiguous, negated, and unsupported negatives")
    if not required_tools <= tools:
        raise ValueError(f"holdout misses supported tools: {sorted(required_tools - tools)}")


def row_id_for(value: str) -> str:
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--check", action="store_true", help="audit only; do not write JSONL")
    args = parser.parse_args()
    rows = read_source()
    audit(rows)
    if not args.check:
        args.output.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
                               encoding="utf-8")
    positives = sum(bool(row["expected"]) for row in rows)
    print(f"Audited {len(rows)} held-out rows ({positives} positive, {len(rows) - positives} negative).")
    if not args.check:
        print(f"Wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
