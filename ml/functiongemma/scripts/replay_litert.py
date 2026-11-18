"""Replay typed transcripts against a LiteRT-LM bundle without executing actions."""

import argparse
import hashlib
import json
import time
from importlib.metadata import version
from pathlib import Path

import litert_lm as lm

from loopo_functiongemma.artifacts import sha256_file
from loopo_functiongemma.tools import DEVELOPER_PROMPT, TOOLS, validate_plan


class DryRunTool(lm.Tool):
    def __init__(self, schema):
        self.schema = schema

    def get_tool_description(self):
        return self.schema

    def execute(self, parameters):
        raise RuntimeError("This replay cannot execute playback actions")


def predict(engine, row):
    sampler = lm.SamplerConfig(top_k=1, top_p=1.0, temperature=1.0)
    started = time.monotonic()
    try:
        with engine.create_conversation(
            system_message=DEVELOPER_PROMPT,
            tools=[DryRunTool(tool) for tool in TOOLS],
            automatic_tool_calling=False,
            sampler_config=sampler,
            max_output_tokens=64,
        ) as conversation:
            started = time.monotonic()
            reply = conversation.send_message(row["text"])
            latency_ms = (time.monotonic() - started) * 1000
    except (RuntimeError, ValueError) as exc:
        return {
            "id": row["id"], "calls": [], "invalid": True,
            "latency_ms": (time.monotonic() - started) * 1000,
            "raw": "", "structured_calls": [], "error": f"Runtime {type(exc).__name__}: {exc}",
        }
    raw = str(reply.contents)
    structured = [{"name": call.name, "arguments": call.arguments} for call in reply.tool_calls]
    error = None
    try:
        calls = validate_plan(structured)
        if not calls and "<start_function_call>" in raw:
            raise ValueError("Malformed function call")
    except ValueError as exc:
        calls, error = [], str(exc)
    return {
        "id": row["id"], "calls": calls, "invalid": error is not None,
        "latency_ms": latency_ms, "raw": raw, "structured_calls": structured, "error": error,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("datasets", nargs="+", type=Path)
    parser.add_argument("--threads", type=int, default=4)
    args = parser.parse_args()
    if args.threads < 1:
        parser.error("--threads must be positive")
    if len({dataset.stem for dataset in args.datasets}) != len(args.datasets):
        parser.error("dataset filenames must have distinct stems")

    args.output.mkdir(parents=True, exist_ok=True)
    cache = args.output / "cache"
    cache.mkdir(exist_ok=True)
    bundle_hash = sha256_file(args.bundle)
    lm.set_min_log_severity(lm.LogSeverity.ERROR)
    with lm.Engine(str(args.bundle), backend=lm.Backend.CPU(thread_count=args.threads),
                   cache_dir=str(cache)) as engine:
        for dataset in args.datasets:
            payload = dataset.read_bytes()
            rows = [json.loads(line) for line in payload.splitlines()]
            predictions = []
            for index, row in enumerate(rows, start=1):
                predictions.append(predict(engine, row))
                if index % 25 == 0 or index == len(rows):
                    print(f"{dataset.name} {index}/{len(rows)}", flush=True)
            result = {
                "metadata": {
                    "dataset_sha256": hashlib.sha256(payload).hexdigest(),
                    "bundle_sha256": bundle_hash,
                    "runtime": f"LiteRT-LM {version('litert-lm')} CPU, {args.threads} threads",
                    "timing_scope": "send_message only, one fresh conversation per transcript",
                },
                "predictions": predictions,
            }
            destination = args.output / f"{dataset.stem}-litert.json"
            pending = destination.with_suffix(".part")
            pending.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            pending.replace(destination)


if __name__ == "__main__":
    main()
