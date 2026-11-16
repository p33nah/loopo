"""Deterministic template corpus, grouped splits, and JSONL command line tools."""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
import sys
import unicodedata
from pathlib import Path
from typing import Any, Iterable

from .tools import validate_plan

ROW_KEYS = {"id", "text", "expected", "category", "variant", "family", "source"}
SPLITS = ("train", "validation", "test", "challenge")
SEED = 67
ONES = ("", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove")
TEENS = ("dieci", "undici", "dodici", "tredici", "quattordici", "quindici",
         "sedici", "diciassette", "diciotto", "diciannove")
TENS = ("", "", "venti", "trenta", "quaranta", "cinquanta", "sessanta", "settanta", "ottanta", "novanta")


def italian_number(value: int) -> str:
    if value < 10:
        return ONES[value] or "zero"
    if value < 20:
        return TEENS[value - 10]
    if value < 100:
        tens, ones = divmod(value, 10)
        stem = TENS[tens][:-1] if ones in (1, 8) else TENS[tens]
        return stem + ONES[ones]
    if value == 200:
        return "duecento"
    rest = italian_number(value - 100) if value > 100 else ""
    return ("cent" if rest.startswith("ott") else "cento") + rest


NUMBERS = {italian_number(value): str(value) for value in range(201)}


def text_key(text: str) -> str:
    unaccented = "".join(char for char in unicodedata.normalize("NFKD", text.casefold())
                         if not unicodedata.combining(char))
    tokens = re.findall(r"[a-z0-9]+|%|[+-](?=\d)", unaccented)
    normalized = re.sub(r"(?<=\s)(?:per cento|percento|%)(?=\s|$)", "percento", " ".join(tokens))
    return " ".join(NUMBERS.get(token, token) for token in normalized.split())


def _call(name: str, **arguments: int) -> dict[str, Any]:
    return {"name": name, "arguments": arguments}


def _row(text: str, expected: list[dict[str, Any]], category: str, variant: str,
         family: str, source: str = "curated_template") -> dict[str, Any]:
    text = re.sub(r"\s+", " ", text).strip()
    text = re.sub(
        r"\b(1|uno) punti( percentuali)?\b",
        lambda match: ("un" if match[1] == "uno" else "1") + " punto"
        + (" percentuale" if match[2] else ""),
        text,
    )
    digest = hashlib.sha1(f"{family}\0{text}".encode("utf-8")).hexdigest()[:12]
    return {
        "id": f"it-{digest}",
        "text": text,
        "expected": expected,
        "category": category,
        "variant": variant,
        "family": family,
        "source": source,
    }


def _render_asr(text: str) -> str:
    """Apply small, reproducible transcript noise without changing intent."""
    return text.replace("à", "a").replace("è", "e").replace("é", "e").replace("'", " ")


def _base_rows() -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    rows: list[dict[str, Any]] = []

    def add_family(family: str, category: str, phrases: Iterable[tuple[str, str]],
                   expected: list[dict[str, Any]], source: str = "curated_template") -> None:
        for variant, text in phrases:
            rows.append(_row(text, expected, category, variant, family, source))

    resumes = [
        ("resume_plain", "riprendi"), ("resume_plain", "continua"), ("resume_plain", "play"),
        ("resume_natural", "avvia la riproduzione"), ("resume_natural", "fai play"),
        ("resume_dialect", "riparti"),
        ("resume_asr", "riprendi la riproduzione"),
    ]
    restarts = [
        ("restart_plain", "ricomincia"), ("restart_plain", "da capo"),
        ("restart_plain", "da zero"), ("restart_natural", "ricomincia il brano"),
        ("restart_natural", "riparti da capo"), ("restart_natural", "riproduci da zero"),
        ("restart_dialect", "rifacciamo da capo"), ("restart_dialect", "riparti dall'inizio"),
        ("restart_asr", "ricomincia dalla inizio"),
    ]
    pauses = [
        ("pause_plain", "pausa"), ("pause_plain", "ferma"),
        ("pause_natural", "metti in pausa"), ("pause_natural", "ferma la riproduzione"),
        ("pause_dialect", "stop un attimo"), ("pause_asr", "interrompi la riproduzione"),
    ]
    for family, phrase in resumes:
        add_family(family, "resume", [("synthetic_asr" if family.endswith("_asr") else family.split("_")[-1], phrase)], [_call("resume")])
    for family, phrase in restarts:
        variant = "synthetic_asr" if family.endswith("_asr") else family.split("_")[-1]
        text = _render_asr(phrase) if variant == "synthetic_asr" else phrase
        add_family(family, "restart", [(variant, text)], [_call("restart")],
                   "synthetic_asr" if variant == "synthetic_asr" else "curated_template")
    for family, phrase in pauses:
        variant = "synthetic_asr" if family.endswith("_asr") else family.split("_")[-1]
        add_family(family, "stay_paused", [(variant, _render_asr(phrase) if variant == "synthetic_asr" else phrase)],
                   [_call("stay_paused")], "synthetic_asr" if variant == "synthetic_asr" else "curated_template")

    rate_values = (25, 50, 70, 80, 90, 100, 110, 125, 150, 175, 200)
    set_templates = [
        ("set_clean", "velocità a {n} percento", "clean"),
        ("set_natural", "metti la velocità a {n} percento", "natural"),
        ("set_natural2", "porta il ritmo su {n} per cento", "natural"),
        ("set_dialect", "mettimelo ar {n}% de velocità", "dialect"),
        ("set_asr", "ve lo cita a {n} percento", "synthetic_asr"),
        ("set_plain", "imposta la velocità su {n}%", "clean"),
        ("set_plain2", "per la velocità scegli {n}", "clean"),
        ("set_to_percent", "imposta la velocità a {n} percento", "natural"),
        ("set_to_per_cent", "porta la velocità al {n} per cento", "natural"),
        ("set_rhythm", "metti il ritmo su {n}", "natural"),
        ("set_rhythm_target", "metti sul {n}% il ritmo", "clean"),
        ("set_regola_al", "come velocità voglio {n}", "natural"),
        ("set_regola_su", "regola la velocità su {n}%", "natural"),
        ("set_porta_a", "porta la velocità a {n}%", "clean"),
        ("set_porta_al", "al {n} per cento il passo del brano", "clean"),
        ("set_metti_ritmo", "metti il ritmo a {n}%", "natural"),
        ("set_speed_percentuale", "porta la velocità su {n} per cento", "natural"),
        ("set_ritmo_percent", "regola il ritmo su {n} percento", "natural"),
    ]
    for family, template, variant in set_templates:
        phrase_variant = variant
        for value in rate_values:
            text = template.format(n=value)
            if variant == "synthetic_asr":
                text = _render_asr(text)
            add_family(family, "set_rate", [(phrase_variant, text)], [_call("set_rate", percent=value)],
                       "synthetic_asr" if variant == "synthetic_asr" else "curated_template")

    delta_values = (1, 5, 10, 15, 25, 50, 100, 175)
    adjust_templates = [
        ("adjust_up_clean", "aumenta di {n} punti percentuali la velocità", 1, "clean"),
        ("adjust_up_natural", "alza il ritmo di {n} punti percentuali", 1, "natural"),
        ("adjust_up_dialect", "famme salì la velocità di {n} punti percentuali", 1, "dialect"),
        ("adjust_up_asr", "accelera la velocità di {n} per cento", 1, "synthetic_asr"),
        ("adjust_down_clean", "riduci la velocità di {n} percento", -1, "clean"),
        ("adjust_down_natural", "togli {n} punti percentuali dal ritmo", -1, "natural"),
        ("adjust_down_dialect", "abbassa la velocità di {n} percento", -1, "dialect"),
        ("adjust_down_asr", "abbassa di {n} percento la velocita", -1, "synthetic_asr"),
        ("adjust_up_rhythm", "aumenta il ritmo di {n}%", 1, "natural"),
        ("adjust_up_incrementa", "dai {n} punti percentuali in più alla velocità", 1, "clean"),
        ("adjust_up_accelera", "accelera il ritmo di {n} punti percentuali", 1, "natural"),
        ("adjust_up_alza_percent", "alza la velocità di {n}%", 1, "natural"),
        ("adjust_up_aumenta_percent", "dai una spinta di {n} punti al ritmo", 1, "clean"),
        ("adjust_up_speed", "mi dai {n} punti in piu sul tempo del pezzo", 1, "clean"),
        ("adjust_up_asr2", "piu velo ce di {n} percento", 1, "synthetic_asr"),
        ("adjust_down_rhythm", "abbassa il ritmo di {n} percento", -1, "natural"),
        ("adjust_down_riduci", "riduci il ritmo di {n} punti percentuali", -1, "clean"),
        ("adjust_down_diminuisci", "diminuisci la velocità di {n} percento", -1, "natural"),
        ("adjust_down_riduci_speed", "levace {n} punti alla velocità", -1, "dialect"),
    ]
    for family, template, direction, variant in adjust_templates:
        for value in delta_values:
            text = template.format(n=value)
            if variant == "synthetic_asr":
                text = _render_asr(text)
            add_family(family, "adjust_rate", [(variant, text)],
                       [_call("adjust_rate", delta_percent=direction * value)],
                       "synthetic_asr" if variant == "synthetic_asr" else "curated_template")

    compound_sets = [
        ("compound_set_resume", "metti la velocità al {n}% e poi riprendi", "resume", "natural"),
        ("compound_set_restart", "imposta la velocità a {n} percento e poi ricomincia", "restart", "natural"),
        ("compound_set_asr", "ritmo {n} percento poi continua da li", "resume", "synthetic_asr"),
        ("compound_set_speed", "prima la velocità a {n}% poi riparti dalla inizio", "restart", "clean"),
        ("compound_set_rhythm", "imposta il ritmo a {n} percento e poi continua", "resume", "natural"),
        ("compound_set_porta", "porta la velocità a {n}% e poi riavvia il brano", "restart", "natural"),
        ("compound_set_regola", "a {n} per cento il ritmo e ricomincia subito", "restart", "natural"),
        ("compound_set_metti", "metti il ritmo su {n}% e poi avvia", "resume", "clean"),
    ]
    for family, template, ending, variant in compound_sets:
        for value in (50, 75, 100, 125, 150, 200):
            text = template.format(n=value)
            if variant == "synthetic_asr":
                text = _render_asr(text)
            add_family(family, "compound", [(variant, text)],
                       [_call("set_rate", percent=value), _call(ending)],
                       "synthetic_asr" if variant == "synthetic_asr" else "curated_template")

    compound_adjust = [
        ("compound_adjust_up", "aumenta la velocità di {n} percento e poi riprendi", 1, "resume", "natural"),
        ("compound_adjust_down", "rallenta la velocità di {n} percento e poi ricomincia", -1, "restart", "natural"),
        ("compound_adjust_asr", "riduci la velocita di {n}% e poi riprendi", -1, "resume", "synthetic_asr"),
        ("compound_adjust_up2", "alza il ritmo di {n} punti percentuali e poi continua", 1, "resume", "natural"),
        ("compound_adjust_down2", "abbassa la velocità di {n} percento e poi riparti dalla inizio", -1, "restart", "clean"),
        ("compound_adjust_up3", "incrementa la velocità di {n}% e poi ricomincia da capo", 1, "restart", "clean"),
        ("compound_adjust_down3", "diminuisci il ritmo di {n} punti percentuali e poi riprendi", -1, "resume", "natural"),
        ("compound_adjust_asr2", "rallenta il ritmo di {n} percento e poi continua", -1, "resume", "synthetic_asr"),
    ]
    for family, template, direction, ending, variant in compound_adjust:
        for value in (1, 5, 25, 50, 100, 175):
            text = template.format(n=value)
            if variant == "synthetic_asr":
                text = _render_asr(text)
            add_family(family, "compound", [(variant, text)],
                       [_call("adjust_rate", delta_percent=direction * value), _call(ending)],
                       "synthetic_asr" if variant == "synthetic_asr" else "curated_template")

    challenge_specs = [
        ("ambig_rate", "ambiguous", "mettila più veloce", "clean"),
        ("ambig_rate_number", "ambiguous", "porta la velocità", "natural"),
        ("ambig_resume_restart", "ambiguous", "riparti o ricomincia", "natural"),
        ("ambig_rate_conflict", "ambiguous", "aumenta al 50 percento", "clean"),
        ("negated_resume", "negated", "non riprendere", "clean"),
        ("negated_pause", "negated", "non mettere in pausa", "natural"),
        ("negated_restart", "negated", "non ricominciare", "clean"),
        ("unsupported_seek", "unsupported", "vai al minuto due", "clean"),
        ("unsupported_loop", "unsupported", "attiva il loop", "clean"),
        ("unsupported_ramp", "unsupported", "aumenta gradualmente la velocità", "clean"),
        ("unsupported_stop", "unsupported", "smetti di ascoltare", "natural"),
        ("unsupported_two_actions", "unsupported", "riprendi e poi metti in pausa", "clean"),
        ("range_low", "out_of_range", "imposta la velocità al 20 percento", "clean"),
        ("range_high", "out_of_range", "imposta la velocità al 201 percento", "clean"),
        ("delta_zero", "out_of_range", "aumenta la velocità di 0 percento", "clean"),
        ("delta_too_high", "out_of_range", "aumenta la velocità di 176 percento", "clean"),
        ("compound_conflict", "ambiguous", "metti al 120 percento e poi pausa", "natural"),
        ("compound_order", "unsupported", "riprendi e poi imposta al 120 percento", "clean"),
        ("compound_three", "unsupported", "imposta al 120 percento e poi riprendi e poi pausa", "clean"),
        ("foreign", "unsupported", "cambia traccia", "clean"),
        ("unrelated", "unrelated", "che tempo fa oggi", "clean"),
        ("noisy_ambiguous", "ambiguous", "velocita cento riprendi forse", "synthetic_asr"),
    ]
    challenge = [
        _row(text, [], category, variant, f"challenge_{family}", "curated_challenge")
        for family, category, text, variant in challenge_specs
    ]
    challenge.extend([
        _row("a bassa la velocità al sessantaquattro e poi riavvia il prezzo",
             [_call("set_rate", percent=64), _call("restart")], "compound",
             "reconstructed_asr", "challenge_phone_split_word", "issue_66_redmi_pilot"),
        _row("potresti proseguire con l'ascolto per cortesia", [_call("resume")], "resume",
             "unseen_paraphrase", "challenge_phone_resume", "issue_66_redmi_pilot"),
        _row("fammi sentire ancora il brano dall'attacco", [_call("restart")], "restart",
             "unseen_paraphrase", "challenge_from_attack", "curated_challenge"),
        _row("piantala con la musica un secondo", [_call("stay_paused")], "stay_paused",
             "dialect", "challenge_stop_music", "curated_challenge"),
        _row("leva otto punti percentuali al tempo", [_call("adjust_rate", delta_percent=-8)],
             "adjust_rate", "unseen_paraphrase", "challenge_subtract_points", "curated_challenge"),
        _row("tienilo ai tre quarti della velocità normale", [_call("set_rate", percent=75)],
             "set_rate", "unseen_paraphrase", "challenge_fraction", "curated_challenge"),
        _row("ripeti il loop cinque volte", [], "unsupported", "clean",
             "challenge_repetitions", "curated_challenge"),
        _row("famme cinque giri e ogni volta vai su del cinque", [], "unsupported", "dialect",
             "challenge_ramp_dialect", "curated_challenge"),
        _row("metti il lupe cinque volte parti da sessanta e aumenta cinque", [], "unsupported",
             "synthetic_asr", "challenge_ramp_asr", "curated_challenge"),
        _row("riparti dal sessanta per cento", [], "ambiguous", "natural",
             "challenge_rate_or_seek", "curated_challenge"),
    ])
    rows.extend(_curated_rows())
    return rows, challenge


def _split_for_family(family: str) -> str:
    value = int(hashlib.sha256(family.encode("utf-8")).hexdigest()[:8], 16) % 100
    return "train" if value < 80 else "validation" if value < 90 else "test"


def _curated_rows() -> list[dict[str, Any]]:
    """Independent authored families for real Italian and rejection in each split."""
    specs = {
        "train": [
            ("readout", "set_rate", "Rallenta al settanta per cento.", [_call("set_rate", percent=70)]),
            ("readout", "set_rate", "Rallenta al settantadue per cento.", [_call("set_rate", percent=72)]),
            ("dai_rate", "set_rate", "Dai, metti a sessantacinque la velocità!", [_call("set_rate", percent=65)]),
            ("dai_rate", "set_rate", "Dai, metti a settantotto la velocità!", [_call("set_rate", percent=78)]),
            ("rel_words", "adjust_rate", "Aumenta la velocità del cinque per cento.", [_call("adjust_rate", delta_percent=5)]),
            ("rel_words", "adjust_rate", "Aumenta la velocità del sette per cento.", [_call("adjust_rate", delta_percent=7)]),
            ("rel_down_words", "adjust_rate", "Togli dodici punti alla velocità.", [_call("adjust_rate", delta_percent=-12)]),
            ("rel_down_words", "adjust_rate", "Togli sei punti alla velocità.", [_call("adjust_rate", delta_percent=-6)]),
            ("play_word", "resume", "Riproduci.", [_call("resume")]),
            ("play_word", "resume", "Famme sentì il pezzo da qui.", [_call("resume")]),
            ("hold", "stay_paused", "Lascia il brano fermo un attimo.", [_call("stay_paused")]),
            ("reset", "restart", "Rimetti il brano dall'inizio.", [_call("restart")]),
            ("miss_amount", "ambiguous", "Alza la velocità di un po'.", []),
            ("miss_amount", "ambiguous", "Abbassa la velocità, quanto vuoi tu.", []),
            ("conflict_play", "ambiguous", "Avvia il brano e lascialo in pausa.", []),
            ("conflict_rate", "ambiguous", "Velocità al settanta oppure all'ottanta.", []),
            ("invalid_set", "out_of_range", "Fammi ascoltare al dieci per cento.", []),
            ("invalid_set", "out_of_range", "Porta il ritmo al trecento per cento.", []),
            ("invalid_delta", "out_of_range", "Rallenta di duecento punti percentuali.", []),
            ("negation", "negated", "Aspetta, non far partire il pezzo.", []),
            ("negation", "negated", "Non cambiare il ritmo.", []),
            ("describe", "unrelated", "Suonavo questo brano da bambina.", []),
            ("describe", "unrelated", "Quanto costa questo strumento?", []),
            ("loop_edit", "unsupported", "Sposta il punto B due battute avanti.", []),
            ("loop_edit", "unsupported", "Fissa il marcatore A qui.", []),
            ("loop_runs", "unsupported", "Voglio tre ripetizioni da A a B.", []),
            ("loop_runs", "unsupported", "Fai sei giri del tratto selezionato.", []),
            ("continuous", "unsupported", "Parti al sessanta e aumenta due punti a ogni giro.", []),
            ("continuous", "unsupported", "Aumenta un po' il tempo a ogni passaggio del loop.", []),
            ("seek", "unsupported", "Salta al ritornello.", []),
            ("seek", "unsupported", "Vai a metà brano.", []),
            ("stop_reset", "unsupported", "Ferma e azzera la posizione senza ripartire.", []),
            ("unknown", "unsupported", "Apri il menu delle impostazioni.", []),
            ("unknown", "unsupported", "Carica la registrazione di ieri.", []),
            ("asr_split", "adjust_rate", "a bassa di cinque punti la velocita", [_call("adjust_rate", delta_percent=-5)]),
            ("asr_omission", "compound", "velocita su settanta poi fai play", [_call("set_rate", percent=70), _call("resume")]),
        ],
        "validation": [
            ("listen", "resume", "Lasciami sentire il resto.", [_call("resume")]),
            ("suspend", "stay_paused", "Sospendi qui l'ascolto.", [_call("stay_paused")]),
            ("first_note", "restart", "Vai di nuovo alla prima nota e suona.", [_call("restart")]),
            ("unknown_slot", "ambiguous", "Riduci quanto avevi detto.", []),
            ("two_targets", "ambiguous", "Metti la velocità sia a sessanta sia a cento.", []),
            ("volume", "unsupported", "Alza il volume di tre tacche.", []),
            ("timer", "unrelated", "Sono già passati dieci minuti?", []),
            ("zero_rate", "out_of_range", "Regola a zero il tempo del brano.", []),
        ],
        "test": [
            ("standard_start", "resume", "Avvia.", [_call("resume")]),
            ("standard_restart", "restart", "Riavvia la riproduzione.", [_call("restart")]),
            ("standard_pause", "stay_paused", "Interrompi.", [_call("stay_paused")]),
            ("standard_chain", "compound", "Velocità su sessantasei e poi da zero.",
             [_call("set_rate", percent=66), _call("restart")]),
            ("standard_rel_chain", "compound", "Diminuisci di quattro punti percentuali il ritmo e poi avvia la riproduzione.",
             [_call("adjust_rate", delta_percent=-4), _call("resume")]),
            ("continue_here", "resume", "Da qui puoi proseguire con il pezzo.", [_call("resume")]),
            ("hold_transport", "stay_paused", "Tieni lì la riproduzione, aspetta.", [_call("stay_paused")]),
            ("start_again", "restart", "Rifai partire la traccia dal principio.", [_call("restart")]),
            ("set_words", "set_rate", "Passa al sessantasei per cento della velocità originale.", [_call("set_rate", percent=66)]),
            ("rel_words_held", "adjust_rate", "Scala indietro il tempo di nove punti.", [_call("adjust_rate", delta_percent=-9)]),
            ("asr_word_boundary", "compound", "ab bassa di tre punti il ritmo e vai avanti con il pezzo",
             [_call("adjust_rate", delta_percent=-3), _call("resume")]),
            ("asr_unresolved", "ambiguous", "velocita un numero fra ottanta e novanta", []),
            ("hesitate", "ambiguous", "Ora riprendi, o forse tienilo fermo.", []),
            ("instrument", "unrelated", "Come si accorda la chitarra?", []),
            ("record", "unsupported", "Fai partire la registrazione del microfono.", []),
            ("repeat_set", "unsupported", "Metti in loop da A a B per cinque volte.", []),
            ("invalid_word", "out_of_range", "Tienilo al quindici percento.", []),
            ("wrong_direction", "ambiguous", "Aumenta e abbassa la velocità di dieci punti.", []),
            ("quoted", "unrelated", "Ieri ho detto riprendi e non mi ha sentito.", []),
            ("forward", "unsupported", "Vai avanti di venti secondi.", []),
            ("fade", "unsupported", "Aumenta la dissolvenza del loop.", []),
            ("outside_range", "out_of_range", "Metti la velocità al duecentodieci.", []),
            ("negated_short", "negated", "Non avviare ancora.", []),
        ],
    }
    return [
        _row(text, expected, category, "synthetic_asr" if family.startswith("asr_") else (
            "clean" if family.startswith("standard_") else "unseen_paraphrase" if split == "test" else "natural"
        ), f"authored_{split}_{family}", "synthetic_asr" if family.startswith("asr_") else "curated_seed")
        for split, entries in specs.items()
        for family, category, text, expected in entries
    ]


def _expand_train(train: list[dict[str, Any]], target: int) -> list[dict[str, Any]]:
    if not 2000 <= target <= 5000:
        raise ValueError("--expand-train must target between 2000 and 5000 training rows")
    examples = {}
    for row in train:
        amount = re.search(r"\d+", row["text"])
        if row["category"] in {"set_rate", "adjust_rate", "compound"} and amount and int(amount[0]) > 1:
            examples.setdefault(row["family"], row)
    known = {text_key(row["text"]) for row in train}
    candidates = []
    for row in examples.values():
        name = row["expected"][0]["name"]
        key = "percent" if name == "set_rate" else "delta_percent"
        direction = -1 if row["expected"][0]["arguments"][key] < 0 else 1
        values = range(25, 201) if name == "set_rate" else range(1, 176)
        for number in values:
            surface = italian_number(number) if number % 2 else str(number)
            text = re.sub(r"\d+", surface, row["text"], count=1)
            variant = "synthetic_asr" if row["variant"] == "synthetic_asr" else "expanded_numeric"
            calls = [_call(name, **{key: direction * number}), *row["expected"][1:]]
            candidate = _row(text, calls, row["category"], variant, row["family"], "generated_expansion")
            normalized = text_key(candidate["text"])
            if normalized in known:
                continue
            known.add(normalized)
            candidates.append(candidate)
    random.Random(SEED).shuffle(candidates)
    if len(candidates) + len(train) < target:
        raise ValueError("not enough unique training expansions")
    return train + candidates[:target - len(train)]

def generate_dataset(expand_train: int | None = None) -> dict[str, list[dict[str, Any]]]:
    """Create deterministic grouped train/validation/test splits and challenge set."""
    base, challenge = _base_rows()
    splits: dict[str, list[dict[str, Any]]] = {name: [] for name in SPLITS}
    for row in base:
        family = row["family"]
        split = family.split("_", 2)[1] if family.startswith("authored_") else _split_for_family(family)
        splits[split].append(row)
    splits["challenge"] = challenge
    if expand_train is not None:
        splits["train"] = _expand_train(splits["train"], expand_train)
    return splits


def _write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for row in rows:
            stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")


def write_dataset(path: str | Path, expand_train: int | None = None) -> dict[str, int]:
    output = Path(path)
    dataset = generate_dataset(expand_train)
    for split, rows in dataset.items():
        _write_jsonl(output / f"{split}.jsonl", rows)
    return {split: len(rows) for split, rows in dataset.items()}


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows = []
    with path.open(encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            if not line.strip():
                raise ValueError(f"{path.name}:{number}: blank lines are not allowed")
            try:
                row = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"{path.name}:{number}: invalid JSON: {error.msg}") from error
            if not isinstance(row, dict):
                raise ValueError(f"{path.name}:{number}: row must be an object")
            rows.append(row)
    return rows


def validate_dataset(path: str | Path) -> dict[str, int]:
    """Validate split files, row schema, plans, IDs, and family isolation."""
    root = Path(path)
    datasets = {split: _read_jsonl(root / f"{split}.jsonl") for split in SPLITS}
    all_ids: set[str] = set()
    all_texts: dict[str, tuple[str, list[dict]]] = {}
    shapes: dict[str, str] = {}
    family_sets: dict[str, set[str]] = {}
    for split, rows in datasets.items():
        families: set[str] = set()
        for index, row in enumerate(rows, 1):
            where = f"{split}.jsonl:{index}"
            if set(row) != ROW_KEYS:
                raise ValueError(f"{where}: fields must be exactly {sorted(ROW_KEYS)}")
            for key in ("id", "text", "category", "variant", "family", "source"):
                if not isinstance(row[key], str) or not row[key].strip():
                    raise ValueError(f"{where}: {key} must be a non-empty string")
            if row["id"] in all_ids:
                raise ValueError(f"{where}: duplicate id {row['id']!r}")
            all_ids.add(row["id"])
            if not isinstance(row["expected"], list):
                raise ValueError(f"{where}: expected must be a list")
            try:
                validate_plan(row["expected"])
            except ValueError as error:
                raise ValueError(f"{where}: {error}") from error
            if re.search(r"<[a-z_]+>", row["text"]):
                raise ValueError(f"{where}: control tokens are not valid transcription text")
            normalized = text_key(row["text"])
            if normalized in all_texts:
                previous, label = all_texts[normalized]
                problem = "duplicate text" if label == row["expected"] else "contradictory labels"
                raise ValueError(f"{where}: {problem} with {previous}")
            all_texts[normalized] = (where, row["expected"])
            # Numeric variants of a source template must land in the same split.
            if any(call["arguments"] for call in row["expected"]):
                shape = re.sub(r"\d+", "#", normalized)
                if shapes.setdefault(shape, split) != split:
                    raise ValueError(f"{where}: template leakage across splits: {shape!r}")
            families.add(row["family"])
        family_sets[split] = families
    for left, right in (("train", "validation"), ("train", "test"), ("validation", "test")):
        overlap = family_sets[left] & family_sets[right]
        if overlap:
            raise ValueError(f"family leakage between {left} and {right}: {sorted(overlap)[:3]}")
    base_families = family_sets["train"] | family_sets["validation"] | family_sets["test"]
    overlap = base_families & family_sets["challenge"]
    if overlap:
        raise ValueError(f"challenge families overlap base splits: {sorted(overlap)[:3]}")
    return {split: len(rows) for split, rows in datasets.items()}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    generate = commands.add_parser("generate", help="write deterministic JSONL splits")
    generate.add_argument("path", type=Path)
    generate.add_argument(
        "--expand-train", type=int, metavar="ROWS",
        help="expand only training to 2000-5000 rows using numeric rate variants",
    )
    validate = commands.add_parser("validate", help="validate generated split files")
    validate.add_argument("path", type=Path)
    args = parser.parse_args(argv)
    try:
        counts = write_dataset(args.path, args.expand_train) if args.command == "generate" else validate_dataset(args.path)
    except (OSError, ValueError) as error:
        print(f"dataset error: {error}", file=sys.stderr)
        return 2
    print(" ".join(f"{split}={count}" for split, count in counts.items()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
