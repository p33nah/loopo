"""Model identity, local inference and export preparation."""

import argparse
import json
import platform
from importlib.metadata import version
from pathlib import Path
from time import perf_counter_ns

from .protocol import STOPS, parse_native, render_prompt, training_features

MODEL_ID = "google/functiongemma-270m-it"
MODEL_REVISION = "39eccb091651513a5dfb56892d3714c1b5b8276c"
MAX_NEW_TOKENS = 64


def load_tokenizer():
    from transformers import AutoTokenizer

    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID, revision=MODEL_REVISION)
    stop_ids(tokenizer)
    return tokenizer


def stop_ids(tokenizer) -> list[int]:
    ids = []
    for token in STOPS:
        token_id = tokenizer.convert_tokens_to_ids(token)
        if token_id is None or token_id == tokenizer.unk_token_id:
            raise ValueError(f"official tokenizer is missing {token}")
        ids.append(token_id)
    return ids


def load_base(*, full_precision: bool = False):
    import torch
    from transformers import AutoModelForCausalLM

    dtype = torch.float32
    if not full_precision and torch.cuda.is_available() and torch.cuda.is_bf16_supported(including_emulation=False):
        dtype = torch.bfloat16
    return AutoModelForCausalLM.from_pretrained(MODEL_ID, revision=MODEL_REVISION, dtype=dtype)


def require_adapter_revision(adapter: Path) -> None:
    metadata = json.loads((adapter.parent / "run.json").read_text(encoding="utf-8"))
    if metadata["model"] != MODEL_ID or metadata["revision"] != MODEL_REVISION:
        raise ValueError("adapter base model does not match this experiment")


def predictions(rows: list[dict], adapter: Path | None = None) -> tuple[list[dict], dict]:
    import torch
    from peft import PeftModel

    start = perf_counter_ns()
    tokenizer = load_tokenizer()
    model = load_base()
    if adapter:
        require_adapter_revision(adapter)
        model = PeftModel.from_pretrained(model, adapter)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model.to(device).eval()
    stops = stop_ids(tokenizer)
    if device == "cuda":
        torch.cuda.synchronize()
        torch.cuda.reset_peak_memory_stats()
    metadata = {
        "model": MODEL_ID,
        "revision": MODEL_REVISION,
        "adapter": str(adapter) if adapter else None,
        "device": torch.cuda.get_device_name() if device == "cuda" else platform.processor() or "CPU",
        "platform": platform.platform(),
        "dependencies": {package: version(package) for package in ("torch", "transformers", "peft")},
        "dtype": str(model.dtype),
        "load_ms": (perf_counter_ns() - start) / 1e6,
        "parameter_bytes": sum(p.numel() * p.element_size() for p in model.parameters()),
        "threads": torch.get_num_threads(),
        "warmups": 1,
        "decoding": {"do_sample": False, "max_new_tokens": MAX_NEW_TOKENS},
        "timing_scope": "transcript tokenization, model generation, native parsing",
    }

    @torch.inference_mode()
    def predict(row: dict) -> dict:
        start = perf_counter_ns()
        prompt = render_prompt(tokenizer, row["text"])
        inputs = {key: torch.tensor([values], device=device) for key, values in prompt.items()}
        if device == "cuda":
            torch.cuda.synchronize()
        generation_start = perf_counter_ns()
        generated = model.generate(
            **inputs, do_sample=False, max_new_tokens=MAX_NEW_TOKENS,
            eos_token_id=stops, pad_token_id=tokenizer.pad_token_id,
        )[0, inputs["input_ids"].shape[1]:]
        if device == "cuda":
            torch.cuda.synchronize()
        generation_ms = (perf_counter_ns() - generation_start) / 1e6
        raw = tokenizer.decode(generated, skip_special_tokens=False, clean_up_tokenization_spaces=False)
        error = None
        try:
            calls = parse_native(raw)
        except ValueError as exc:
            calls, error = [], str(exc)
        return {
            "id": row["id"], "calls": calls, "invalid": error is not None, "error": error,
            "raw": raw, "generation_ms": generation_ms, "latency_ms": (perf_counter_ns() - start) / 1e6,
        }

    # Use a fixed synthetic warmup, independent of evaluation rows.
    predict({"id": "warmup", "text": "Riprendi."})
    results = [predict(row) for row in rows]
    if device == "cuda":
        metadata["cuda_peak_allocated_bytes"] = torch.cuda.max_memory_allocated()
    return results, metadata


def merge(adapter: Path, output: Path) -> None:
    from peft import PeftModel

    require_adapter_revision(adapter)
    if output.exists() and any(output.iterdir()):
        raise ValueError("merged output directory must be empty")
    tokenizer = load_tokenizer()
    model = PeftModel.from_pretrained(load_base(full_precision=True), adapter).merge_and_unload(safe_merge=True)
    model.float().save_pretrained(output)
    tokenizer.save_pretrained(output)
    # The Torch exporter needs the original SentencePiece model as well as tokenizer.json.
    from huggingface_hub import hf_hub_download
    from shutil import copyfile

    copyfile(hf_hub_download(MODEL_ID, "tokenizer.model", revision=MODEL_REVISION), output / "tokenizer.model")
    (output / "loopo-export.json").write_text(
        json.dumps({"model": MODEL_ID, "revision": MODEL_REVISION, "adapter": str(adapter)}, indent=2) + "\n",
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate-format")
    validate.add_argument("dataset", type=Path)
    validate.add_argument("--max-length", type=int, default=1024)
    export = commands.add_parser("merge")
    export.add_argument("adapter", type=Path)
    export.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.command == "merge":
        merge(args.adapter, args.output)
        print(f"Merged Hugging Face checkpoint written to {args.output}")
    else:
        tokenizer = load_tokenizer()
        for split in ("train", "validation", "test", "challenge"):
            rows = [json.loads(line) for line in (args.dataset / f"{split}.jsonl").read_text(encoding="utf-8").splitlines()]
            lengths = [len(training_features(tokenizer, row, args.max_length)["input_ids"]) for row in rows]
            print(f"{split}: {len(rows)} native turns validated, longest {max(lengths)} tokens")


if __name__ == "__main__":
    main()
