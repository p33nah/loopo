"""Build a balanced Italian command corpus or inspect an existing dataset."""

from __future__ import annotations

import argparse
import random
import re
import sys
from collections.abc import Iterator
from collections import Counter, defaultdict, deque
from pathlib import Path

from .dataset import (
    SEED,
    SPLITS,
    _expand_train,
    _read_jsonl,
    _row,
    _write_jsonl,
    generate_dataset,
    text_key,
    validate_splits,
)
from .tools import Call, validate_plan

EXAMPLES = Path(__file__).with_name("examples")
REASONS = {"ambiguous", "negated", "out_of_range", "unrelated", "unsupported"}
CONTEXTS = (
    "Senti, {phrase}.", "Per favore, {phrase}.", "Loopo, {phrase}.", "Dunque, {phrase}.",
    "{phrase}, per piacere.", "Scusa, {phrase}.", "Per cortesia, {phrase}.",
    "Ehi, {phrase}.", "Ascolta, {phrase}.", "{phrase}, grazie.",
    "Allora, {phrase}.", "Ti dico questo: {phrase}.", "{phrase}, mi raccomando.",
    "Ti chiedo una cosa: {phrase}.", "Ciao Loopo, {phrase}.", "{phrase}, se puoi.",
)


def parse_label(label: str) -> tuple[str, list[Call]]:
    if label.startswith("reject:"):
        reason = label.removeprefix("reject:")
        if reason not in REASONS:
            raise ValueError(f"unknown rejection reason: {reason!r}")
        return reason, []

    calls: list[Call] = []
    for action in label.split("+"):
        name, separator, argument = action.strip().partition(":")
        arguments = {}
        if separator:
            key = {"set_rate": "percent", "adjust_rate": "delta_percent"}.get(name)
            if key is None or not re.fullmatch(r"-?[0-9]+", argument):
                raise ValueError(f"bad action value: {action!r}")
            arguments[key] = int(argument)
        calls.append({"name": name, "arguments": arguments})
    checked = validate_plan(calls)
    return ("compound" if len(checked) == 2 else checked[0]["name"]), checked


def read_examples(path: Path, source: str) -> list[dict]:
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            label, family, phrase = (part.strip() for part in line.split("|", 2))
            if not re.fullmatch(r"[a-z][a-z0-9_-]*", family):
                raise ValueError("use a lowercase family slug such as negated_restart")
            if not text_key(phrase) or "|" in phrase:
                raise ValueError("expected one transcript without a pipe")
            category, calls = parse_label(label)
            rows.append(_row(phrase, calls, category, "natural", f"balanced_{family}", source))
        except ValueError as error:
            raise ValueError(f"{path}:{number}: {error}") from error
    if not rows:
        raise ValueError(f"{path}: no examples")
    return rows


def numeric_shape(phrase: str) -> str | None:
    normalized = text_key(phrase)
    return re.sub(r"\d+", "#", normalized) if re.search(r"\d", normalized) else None


def context_variants(rows: list[dict]) -> Iterator[dict]:
    for row in rows:
        phrase = row["text"].rstrip(" .!?")
        lower = phrase[0].lower() + phrase[1:]
        for pattern in CONTEXTS:
            suffix = pattern.startswith("{")
            if suffix and row["text"].endswith("?"):
                continue
            surface = pattern.format(phrase=phrase if suffix else lower)
            if row["text"].endswith("?"):
                surface = surface.removesuffix(".") + "?"
            yield _row(surface, row["expected"], row["category"], "generated_context",
                       row["family"], "generated_context")


def numeric_rejections() -> Iterator[dict]:
    frames = (
        ("negated", "keep_default", "Non portare il tempo al {} percento, lascialo com'è", range(30, 126, 5)),
        ("negated", "no_increase", "Evita di darmi {} punti di velocità in più", range(2, 23)),
        ("ambiguous", "number_choices", "Due velocità possibili, {} o {} percento. Quale?", range(45, 111)),
        ("unsupported", "practice_timer", "Avvia il conto alla rovescia da {} secondi", range(15, 91)),
        ("unsupported", "transpose", "Trasponi il pezzo di {} semitoni", range(1, 25)),
        ("out_of_range", "below_min", "Per studiarlo voglio appena il {} percento della velocità", range(1, 25)),
        ("out_of_range", "above_max", "Provo ad ascoltarlo al {} percento del tempo originale", range(201, 241)),
        ("out_of_range", "delta_too_large", "Togli {} punti percentuali tutti in una volta", range(176, 211)),
    )
    for category, family, pattern, numbers in frames:
        for number in numbers:
            phrase = pattern.format(number, number + 12)
            yield _row(phrase, [], category, "expanded_numeric", f"balanced_{family}", "generated_rejection")


def extend_bucket(train: list[dict], pool: list[dict], count: int, rng: random.Random) -> list[dict]:
    if count < 0:
        raise ValueError("target is too small to retain authored rows at this rejection ratio")
    categories: dict[str, dict[str, list[dict]]] = defaultdict(lambda: defaultdict(list))
    rng.shuffle(pool)
    for row in pool:
        categories[row["category"]][row["family"]].append(row)
    families = {category: deque(deque(rows) for rows in groups.values()) for category, groups in categories.items()}
    totals = Counter(row["category"] for row in train)
    added = []
    for _ in range(count):
        if not families:
            raise ValueError("not enough distinct examples at this target and rejection ratio")
        category = min(families, key=lambda key: (totals[key], key))
        family_rows = families[category].popleft()
        added.append(family_rows.popleft())
        totals[category] += 1
        if family_rows:
            families[category].append(family_rows)
        if not families[category]:
            del families[category]
    return added


def check_authored_splits(splits: dict[str, list[dict]], new_train: list[dict],
                          new_validation: list[dict]) -> set[str]:
    validate_splits(splits)
    evaluation = [row for split in ("validation", "test", "challenge") for row in splits[split]]
    protected = {numeric_shape(row["text"]) for row in evaluation} - {None}
    for row in new_train:
        if numeric_shape(row["text"]) in protected:
            raise ValueError(f"training example shares a numeric template with evaluation: {row['text']!r}")
    train_shapes = {numeric_shape(row["text"]) for row in splits["train"]} - {None}
    for row in new_validation:
        if numeric_shape(row["text"]) in train_shapes:
            raise ValueError(f"validation example shares a numeric template with training: {row['text']!r}")
    return protected


def personal_corpus(examples: list[Path]) -> dict[str, list[dict]]:
    train = [row for path in examples for row in read_examples(path, "contributed")]
    splits = {**generate_dataset(), "train": train}
    check_authored_splits(splits, train, [])
    return splits


def build_corpus(
    rows: int = 3000,
    reject_percent: int = 40,
    examples: list[Path] | None = None,
) -> dict[str, list[dict]]:
    if not 1000 <= rows <= 5000 or not 1 <= reject_percent <= 99:
        raise ValueError("use 1000–5000 training rows and a rejection percentage from 1 to 99")
    original = generate_dataset()
    manual_train = read_examples(EXAMPLES / "train_commands.txt", "curated_balanced")
    manual_train += read_examples(EXAMPLES / "train_rejections.txt", "curated_balanced")
    for path in examples or []:
        manual_train += read_examples(path, "contributed")
    manual_validation = read_examples(EXAMPLES / "validation.txt", "curated_balanced")
    splits = {
        **original,
        "train": [*original["train"], *manual_train],
        "validation": [*original["validation"], *manual_validation],
    }
    protected = check_authored_splits(splits, manual_train, manual_validation)

    original_train = original["train"]
    train = splits["train"]
    rejected = sum(not row["expected"] for row in train)
    desired_rejects = rows * reject_percent // 100
    needed = {False: desired_rejects - rejected, True: rows - desired_rejects - (len(train) - rejected)}
    known = {text_key(row["text"]) for split in SPLITS for row in splits[split]}
    pools: dict[bool, list[dict]] = {False: [], True: []}
    generated = (
        *_expand_train(original_train, 5000)[len(original_train):],
        *numeric_rejections(),
        *context_variants(manual_train),
    )
    for row in generated:
        key = text_key(row["text"])
        if key in known or numeric_shape(row["text"]) in protected:
            continue
        pools[bool(row["expected"])].append(row)
        known.add(key)

    rng = random.Random(SEED)
    for has_calls, count in needed.items():
        train.extend(extend_bucket([r for r in train if bool(r["expected"]) == has_calls],
                                   pools[has_calls], count, rng))
    validate_splits(splits)
    return splits


def print_summary(dataset: dict[str, list[dict]]) -> None:
    for split, samples in dataset.items():
        no_call = sum(not row["expected"] for row in samples)
        families = len({row["family"] for row in samples})
        categories = Counter(row["category"] for row in samples)
        label_counts = ", ".join(f"{name}={count}" for name, count in sorted(categories.items()))
        print(f"{split}: {len(samples)} rows, {families} families, {no_call} no-call")
        print(f"  {label_counts}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    generate = commands.add_parser("generate", help="write a balanced corpus and frozen test splits")
    generate.add_argument("path", type=Path)
    generate.add_argument("--profile", choices=("balanced", "contrastive"), default="balanced")
    generate.add_argument("--holdout", type=Path, action="append", default=[],
                          help="protect and emit an evaluation-only example file (contrastive only)")
    generate.add_argument("--rows", type=int, default=3000)
    generate.add_argument("--reject-percent", type=int, default=40)
    generate.add_argument("--examples", type=Path, action="append", default=[])
    personal = commands.add_parser("import", help="use only personal training examples with the original evaluation splits")
    personal.add_argument("path", type=Path)
    personal.add_argument("--examples", type=Path, action="append", required=True)
    inspect = commands.add_parser("inspect", help="validate and summarize four dataset split files")
    inspect.add_argument("path", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "inspect":
            dataset = {split: _read_jsonl(args.path / f"{split}.jsonl") for split in SPLITS}
            validate_splits(dataset)
        else:
            probes = {}
            if args.command == "import":
                dataset = personal_corpus(args.examples)
            elif args.profile == "contrastive":
                from .contrastive import build_contrastive_corpus

                dataset, probes = build_contrastive_corpus(args.rows, args.reject_percent, args.examples or [], args.holdout)
            else:
                if args.holdout:
                    raise ValueError("--holdout requires --profile contrastive")
                dataset = build_corpus(args.rows, args.reject_percent, args.examples)
            dataset.update(probes)
            for split, samples in dataset.items():
                _write_jsonl(args.path / f"{split}.jsonl", samples)
        print_summary(dataset)
    except (OSError, ValueError) as error:
        print(f"corpus error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
