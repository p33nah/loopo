"""Numeric contrast families for the second FunctionGemma training profile."""

from collections import Counter
from collections.abc import Callable, Iterator
from itertools import chain
from pathlib import Path
import random

from .corpus import EXAMPLES, context_variants, extend_bucket, numeric_rejections, numeric_shape, read_examples
from .dataset import SEED, _call, _row, generate_dataset, italian_number, text_key, validate_splits

FRAMES = (
    ("lower_speed", "Riduci la velocità del brano a {n}", "Riduci la velocità del brano di {n}", -1),
    ("lower_rhythm", "Abbassa il ritmo al {n}", "Abbassa il ritmo di {n}", -1),
    ("decrease_playback", "Diminuisci la velocità di riproduzione fino al {n}", "Diminuisci la velocità di riproduzione di {n}", -1),
    ("slow_track", "Rallenta la traccia al {n}", "Rallenta la traccia di {n}", -1),
    ("raise_speed", "Aumenta la velocità del pezzo fino al {n}", "Aumenta la velocità del pezzo di {n}", 1),
    ("raise_rhythm", "Alza il ritmo di ascolto al {n}", "Alza il ritmo di ascolto di {n}", 1),
    ("raise_playback", "Aumenta la velocità di riproduzione a {n}", "Aumenta la velocità di riproduzione di {n}", 1),
    ("raise_track", "Alza la velocità della traccia al {n}", "Alza la velocità della traccia di {n}", 1),
)
# Pure prohibitions prohibit an action. The positive pause examples request a paused state.
PROHIBITIONS = {
    "non avviare ancora la traccia",
    "per il momento non far suonare nulla",
    "non far ripartire ancora l ascolto",
}
DROP = {"per ora niente audio", "vorrei continuare l esercizio musicale", "ricomincia la sessione musicale"}
NUMBER_EDGES = {1, 2, 23, 24, 25, 26, 68, 69, 70, 71, 72, 99, 100, 101, 174, 175, 176, 199, 200}


def _amount(value: int, words: bool, unit: str) -> str:
    if value == 1 and unit == "punti percentuali":
        return "un punto percentuale" if words else "1 punto percentuale"
    surface = italian_number(value) if words else str(value)
    if words and value > 3 and surface.endswith("tre"):
        surface = surface[:-1] + "é"
    return f"{surface} {unit}" if unit else surface


def _rate_example(frame: tuple[str, str, str, int], n: int, words: bool, relative: bool,
                  unit: str, reject: str | None = None) -> dict:
    family, absolute, delta, direction = frame
    template = delta if relative else absolute
    if reject == "negated":
        verb, rest = template.split(" ", 1)
        infinitive = {"Riduci": "ridurre", "Abbassa": "abbassare", "Diminuisci": "diminuire",
                      "Rallenta": "rallentare", "Aumenta": "aumentare", "Alza": "alzare"}
        template = "Non " + infinitive[verb] + " " + rest
    tool, arguments = ("adjust_rate", {"delta_percent": n * direction}) if relative else ("set_rate", {"percent": n})
    return _row(template.format(n=_amount(n, words, unit)), [] if reject else [_call(tool, **arguments)],
                reject or tool, "number_words" if words else "number_digits",
                "contrastive_" + family, "contrastive_numeric")


def _rate_rows(available: Callable[[dict], bool]) -> Iterator[dict]:
    rng = random.Random(SEED)
    for relative, bounds in ((False, range(25, 201)), (True, range(1, 176))):
        frame_groups = (FRAMES[:4], FRAMES[4:]) if relative else (FRAMES,)
        for n in bounds:
            for frames in frame_groups:
                options = rng.sample(frames, len(frames))
                for words in (False, True):
                    if relative:
                        units = ("punti percentuali", "percento", "per cento") if n > 1 else ("punti percentuali",)
                    else:
                        units = ("percento", "per cento", "")
                    unit = rng.choice(units)
                    for frame in options:
                        row = _rate_example(frame, n, words, relative, unit)
                        if available(row):
                            break
                    else:
                        raise ValueError(f"all contrastive frames for amount {n} overlap existing rows or evaluation")
                    yield row
                    if n in NUMBER_EDGES or n % 5 == 0:
                        yield _rate_example(frame, n, words, relative, unit, "negated")

    for index, frame in enumerate(FRAMES):
        for relative, lower, upper in ((False, 25, 200), (True, 1, 175)):
            for n in (lower - 1, lower, upper, upper + 1):
                words = bool((index + n) % 2)
                unit = "punti percentuali" if relative else "percento"
                yield _rate_example(frame, n, words, relative, unit,
                                    "out_of_range" if n in {lower - 1, upper + 1} else None)

        for n in (69, 70, 71):
            words = bool((index + n) % 2)
            for relative, unit in ((False, ""), (False, "percento"), (True, "percento")):
                yield _rate_example(frame, n, words, relative, unit)
                yield _rate_example(frame, n, words, relative, unit, "negated")

        for n in (34, 48, 58, 62, 69, 70, 71, 84, 99, 107, 123, 161):
            if index not in {n % len(FRAMES), (n + 3) % len(FRAMES)}:
                continue
            words = index != n % len(FRAMES)
            family, absolute, _, _ = frame
            text = absolute.format(n=_amount(n, words, "percento"))
            yield _row(f"{text} o di {_amount(n, words, 'punti percentuali')}?", [], "ambiguous",
                       "number_words" if words else "number_digits",
                       "contrastive_" + family, "contrastive_numeric")


def _review_training(rows: list[dict]) -> Iterator[dict]:
    for row in rows:
        key = text_key(row["text"])
        if key in DROP:
            continue
        if key in PROHIBITIONS:
            yield {**row, "category": "negated", "expected": []}
            continue
        yield row


def build_contrastive_corpus(rows: int, reject_percent: int, examples: list[Path],
                             holdouts: list[Path]) -> tuple[dict[str, list[dict]], dict[str, list[dict]]]:
    if not 1000 <= rows <= 5000 or not 1 <= reject_percent <= 99:
        raise ValueError("use 1000–5000 training rows and a rejection percentage from 1 to 99")
    original = generate_dataset()
    # Keep the historical benchmark and validation bytes available for apples-to-apples comparisons.
    validation = read_examples(EXAMPLES / "validation.txt", "curated_balanced")
    splits = {**original, "validation": [*original["validation"], *validation]}
    probes = {}
    for path in [EXAMPLES / "numeric-replay.txt", *holdouts]:
        if path.stem in probes or path.stem in splits:
            raise ValueError(f"holdout filename collides with another split: {path.name}")
        probe = read_examples(path, f"heldout_{path.stem}")
        if len({row["id"] for row in probe}) != len(probe):
            raise ValueError(f"duplicate example in holdout: {path.name}")
        probes[path.stem] = probe
    eval_rows = [row for name in ("validation", "test", "challenge") for row in splits[name]]
    eval_rows.extend(chain.from_iterable(probes.values()))
    protected_text = {text_key(row["text"]) for row in eval_rows}
    protected_shape = {numeric_shape(row["text"]) for row in eval_rows} - {None}
    protected_family = {row["family"] for row in eval_rows}

    def protected(row: dict) -> bool:
        return (text_key(row["text"]) in protected_text or numeric_shape(row["text"]) in protected_shape
                or row["family"] in protected_family)

    authored = [*original["train"], *read_examples(EXAMPLES / "train_commands.txt", "curated_balanced"),
                *read_examples(EXAMPLES / "train_rejections.txt", "curated_balanced")]
    authored.extend(row for path in examples for row in read_examples(path, "contributed"))
    seen: dict[str, list[dict]] = {}

    def unique(candidates: Iterator[dict]) -> Iterator[dict]:
        for row in candidates:
            if protected(row):
                if row["source"] == "contributed":
                    raise ValueError(f"contributed example overlaps evaluation: {row['text']!r}")
                continue
            key = text_key(row["text"])
            if key in seen:
                if seen[key] != row["expected"]:
                    raise ValueError(f"contradictory labels for {row['text']!r}")
                continue
            seen[key] = row["expected"]
            yield row

    reviewed = list(unique(_review_training(authored)))
    core = list(unique(_rate_rows(lambda row: not protected(row) and text_key(row["text"]) not in seen)))
    train = reviewed + core
    wanted = {False: rows * reject_percent // 100}
    wanted[True] = rows - wanted[False]
    have = Counter(bool(row["expected"]) for row in train)
    if any(have[has_calls] > target for has_calls, target in wanted.items()):
        raise ValueError(f"target is too small for grouped contrastive volume "
                         f"({have[True]} command rows, {have[False]} no-call rows before sampling)")
    candidates = list(unique(chain(numeric_rejections(), context_variants(reviewed))))
    rng = random.Random(SEED)
    for has_calls, target in wanted.items():
        selected = [row for row in train if bool(row["expected"]) == has_calls]
        pool = [row for row in candidates if bool(row["expected"]) == has_calls]
        train.extend(extend_bucket(selected, pool, target - have[has_calls], rng))
    splits["train"] = train
    validate_splits(splits)
    return splits, probes
