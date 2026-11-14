"""Generate held-out Italian transcripts for the voice interpreter comparison."""

import argparse
from collections import Counter
from pathlib import Path
import re
import unicodedata


ASSETS = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/voice-intent-ab"
TARGETS = (
    (25, "venticinque"), (28, "ventotto"), (31, "trentuno"), (42, "quarantadue"),
    (48, "quarantotto"), (53, "cinquantatré"), (60, "sessanta"), (67, "sessantasette"),
    (72, "settantadue"), (75, "settantacinque"), (80, "ottanta"), (86, "ottantasei"),
    (93, "novantatré"), (99, "novantanove"), (100, "cento"), (103, "centotré"),
    (110, "centodieci"), (125, "centoventicinque"), (150, "centocinquanta"),
    (180, "centottanta"), (199, "centonovantanove"), (200, "duecento"),
)
DELTAS = (
    (1, "uno"), (2, "due"), (3, "tre"), (4, "quattro"), (5, "cinque"),
    (7, "sette"), (9, "nove"), (10, "dieci"), (16, "sedici"), (25, "venticinque"),
    (40, "quaranta"), (75, "settantacinque"), (125, "centoventicinque"),
    (175, "centosettantacinque"),
)

TARGET_FORMS = (
    ("clean", "porta la velocità a {n} per cento"),
    ("clean", "imposta il ritmo al {n} percento"),
    ("clean", "regola al {n} la velocità"),
    ("paraphrase", "vorrei ascoltare il brano al {n} percento"),
    ("paraphrase", "mi metti la velocità al {n} per cento per favore"),
    ("paraphrase", "potresti far andare il pezzo al {n} percento"),
    ("paraphrase", "per questa prova mi serve la velocità al {n} percento"),
    ("grammar", "imposta velocità {n} percento"),
    ("grammar", "porta il velocità a {n} per cento"),
    ("grammar", "metti le velocità al {n} percento"),
    ("grammar", "regola velocità {n} per cento"),
)
DELTA_FORMS = (
    ("clean", "aumenta la velocità di {n} punti percentuali",
     "riduci la velocità di {n} punti percentuali"),
    ("clean", "incrementa del {n} percento il ritmo",
     "diminuisci del {n} percento il ritmo"),
    ("paraphrase", "mi alzi la velocità di {n} punti percentuali",
     "mi abbassi la velocità di {n} punti percentuali"),
    ("paraphrase", "puoi aumentare il ritmo di {n} punti percentuali per favore",
     "puoi ridurre il ritmo di {n} punti percentuali per favore"),
    ("grammar", "aumenta velocità di {n} percento",
     "riduci velocità di {n} percento"),
    ("grammar", "aumenta il velocità del {n} percento",
     "riduci il velocità del {n} percento"),
    ("grammar", "incrementa velocità {n} punti percentuali",
     "diminuisci velocità {n} punti percentuali"),
)
PLAYBACK_FORMS = (
    ("resume", "clean", "riparti"),
    ("pause", "clean", "ferma la riproduzione"),
    ("resume", "paraphrase", "puoi rimettere il brano in riproduzione"),
    ("resume", "paraphrase", "vorrei tornare ad ascoltare il pezzo"),
    ("resume", "paraphrase", "fai continuare a suonare la traccia"),
    ("pause", "paraphrase", "mi fermi il brano un attimo per favore"),
    ("pause", "paraphrase", "sospendi la traccia per piacere"),
    ("pause", "paraphrase", "puoi mettere il pezzo in pausa adesso"),
    ("resume", "grammar", "riprendi brano subito"),
    ("resume", "grammar", "fai ripartire il musica"),
    ("pause", "grammar", "ferma la brano"),
    ("pause", "grammar", "metti in pausa traccia"),
)
SUFFIXES = (
    ("clean", "e poi riprendi"),
    ("paraphrase", "quindi fai ripartire il pezzo"),
    ("paraphrase", "dopodiché rimetti in riproduzione la traccia"),
    ("grammar", "poi riprendi brano"),
)
TYPOS = {
    "porta": ("pporta", "poa"),
    "imposta": ("impsota", "impsa"),
    "regola": ("reola", "rola"),
    "aumenta": ("aumneta", "aumta"),
    "riduci": ("ridui", "riui"),
    "incrementa": ("incremeta", "incemeta"),
    "diminuisci": ("diminusci", "diminuci"),
    "riparti": ("riprati", "ripti"),
    "ferma": ("fermma", "fma"),
}
SPLIT_NUMBERS = {
    42: "quaranta due",
    67: "sessanta sette",
    72: "settanta due",
    75: "settanta cinque",
    86: "ottanta sei",
    99: "novanta nove",
    110: "cento dieci",
    150: "cento cinquanta",
    199: "cento novanta nove",
}
NEGATIVES = (
    "non portare la velocità a {n} per cento",
    "imposta il ritmo a {n} e poi non riprendere",
    "se vuoi porta la velocità a {n} percento",
    "forse regola la velocità a {n} per cento",
    "imposta la velocità a {n} oppure a 81",
    "diminuisci e aumenta la velocità di {n} percento",
    "metti il volume a {n} percento",
    "regola la luminosità al {n} percento",
    "quanto ci vuole per arrivare al {n} percento",
    "riprendi e poi imposta la velocità al {n} percento",
    "porta la velocità al {n} percento e poi pausa",
    "porta la velocità al {n} percento e poi riprendi e poi pausa",
    "metti la velocità a {n},5 percento",
    "metti la velocità a -{n} percento",
)


def canonical(text):
    decomposed = unicodedata.normalize("NFD", text.lower())
    plain = "".join(c for c in decomposed if not unicodedata.combining(c))
    return re.sub(r"[^\w%]+", " ", plain.replace("%", " % "), flags=re.UNICODE).strip()


def typo_variants(phrase):
    for match in re.finditer(r"\b\w+\b", phrase):
        variants = TYPOS.get(match.group())
        if variants is not None:
            for edits, typo in enumerate(variants, start=1):
                yield f"typo-{edits}", phrase[:match.start()] + typo + phrase[match.end():]
            return


def source_cases():
    for category, form in TARGET_FORMS:
        for value, word in TARGETS:
            for number in (str(value), word):
                yield category, f"set:{value}", form.format(n=number)
    for category, increase, decrease in DELTA_FORMS:
        for value, word in DELTAS:
            for number in (str(value), word):
                yield category, f"delta:{value}", increase.format(n=number)
                yield category, f"delta:{-value}", decrease.format(n=number)
    for expected, category, phrase in PLAYBACK_FORMS:
        yield category, expected, phrase
    for category, suffix in SUFFIXES:
        for value, word in TARGETS:
            yield category, f"set:{value},resume", f"porta la velocità a {word} percento {suffix}"
        for value, word in DELTAS:
            yield category, f"delta:{-value},resume", f"riduci il ritmo di {word} punti percentuali {suffix}"
    for value, split in SPLIT_NUMBERS.items():
        for form in (
            "imposta la velocità al {n} percento",
            "porta il ritmo a {n} per cento",
            "regola la velocità su {n}",
        ):
            yield "asr-like", f"set:{value}", form.format(n=split)
    for value, word in TARGETS:
        yield "asr-like", f"set:{value},resume", f"velocità {word} per cento e poi riprendi"
    for form in NEGATIVES:
        for value, _ in TARGETS[::2]:
            yield "negative", "reject", form.format(n=value)
    for value in (0, 1, 20, 24, 201, 210, 250, 300):
        yield "negative", "reject", f"imposta la velocità a {value} percento"
    for value in (0, 176, 180, 200, 250):
        yield "negative", "reject", f"aumenta la velocità di {value} punti percentuali"
    for phrase in (
        "riprendi la registrazione", "ferma il download", "avvia il timer",
        "metti in pausa la stampa", "come si fa a riprendere il brano",
        "lui ha messo in pausa la traccia", "non riprendere mai", "riprendi tra dieci secondi",
    ):
        yield "negative", "reject", phrase


def read_reserved():
    texts = []
    for filename, text_column in (
        ("training.txt", 1), ("evaluation.txt", 2), ("vosk-synthetic.txt", 2),
    ):
        for line in (ASSETS / filename).read_text(encoding="utf-8").splitlines():
            texts.extend(line.split("|")[text_column:])
    return {canonical(text) for text in texts}


def generate():
    reserved = read_reserved()
    cases = {}
    for category, expected, phrase in source_cases():
        if canonical(phrase) in reserved:
            continue
        variants = [(category, phrase)]
        if category == "clean":
            variants.extend(typo_variants(phrase))
        for variant_category, transcript in variants:
            key = canonical(transcript)
            if key in reserved:
                continue
            row = (variant_category, expected, phrase, transcript)
            previous = cases.setdefault(key, row)
            if previous[1] != expected:
                raise ValueError(f"Conflicting labels for {transcript!r}")
    return list(cases.values())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Check the committed corpus")
    args = parser.parse_args()
    rows = generate()
    corpus = "".join("|".join(row) + "\n" for row in rows).encode("utf-8")
    target = ASSETS / "generated-evaluation.txt"
    if args.check:
        if target.read_bytes() != corpus:
            parser.error("Regenerate generated-evaluation.txt")
    else:
        target.write_bytes(corpus)
    counts = Counter(row[0] for row in rows)
    print(f"{len(rows)} transcripts: " + ", ".join(f"{key}={value}" for key, value in sorted(counts.items())))


if __name__ == "__main__":
    main()
