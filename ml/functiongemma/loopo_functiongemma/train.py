"""Train a FunctionGemma LoRA adapter with assistant-completion loss."""

import argparse
import hashlib
import json
import logging
import random
import subprocess
import tomllib
from importlib.metadata import version
from pathlib import Path

from .dataset import validate_dataset
from .model import MODEL_ID, MODEL_REVISION, load_base, load_tokenizer
from .protocol import training_features

LOG = logging.getLogger(__name__)


def read_rows(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


def make_trainer(model, tokenizer, train_rows: list[dict], validation_rows: list[dict], config: dict, output: Path):
    import torch
    from datasets import Dataset
    from peft import LoraConfig, TaskType
    from trl import SFTConfig, SFTTrainer

    use_gpu = torch.cuda.is_available()
    bf16 = use_gpu and torch.cuda.is_bf16_supported(including_emulation=False)
    limit = config["max_length"]
    train_dataset = Dataset.from_list([training_features(tokenizer, row, limit) for row in train_rows])
    eval_dataset = Dataset.from_list([training_features(tokenizer, row, limit) for row in validation_rows])
    args = SFTConfig(
        output_dir=str(output),
        seed=config["seed"],
        data_seed=config["seed"],
        max_length=limit,
        max_steps=config["max_steps"],
        num_train_epochs=config["epochs"],
        learning_rate=config["learning_rate"],
        gradient_accumulation_steps=config["gradient_accumulation_steps"],
        per_device_train_batch_size=1,
        per_device_eval_batch_size=1,
        gradient_checkpointing=False,
        dataloader_pin_memory=use_gpu,
        optim="adamw_torch",
        bf16=bf16,
        packing=False,
        dataset_kwargs={"skip_prepare_dataset": True},
        completion_only_loss=True,
        eval_strategy="epoch",
        save_strategy="epoch",
        save_total_limit=2,
        load_best_model_at_end=True,
        metric_for_best_model="eval_loss",
        logging_steps=1,
        report_to=["tensorboard"],
        push_to_hub=False,
    )
    return SFTTrainer(
        model=model, args=args, processing_class=tokenizer,
        train_dataset=train_dataset, eval_dataset=eval_dataset,
        peft_config=LoraConfig(
            task_type=TaskType.CAUSAL_LM, r=8, lora_alpha=16, lora_dropout=0.05,
            target_modules=["q_proj", "v_proj"],
        ),
    )


def train(dataset: Path, config_path: Path, output: Path, resume: bool) -> None:
    import torch
    from transformers import set_seed

    validate_dataset(dataset)
    config = tomllib.loads(config_path.read_text(encoding="utf-8"))
    checkpoint_files = {"adapter_model.safetensors", "trainer_state.json", "optimizer.pt",
                        "scheduler.pt", "rng_state.pth"}
    complete = [
        path for path in output.glob("checkpoint-*")
        if path.name.removeprefix("checkpoint-").isdigit() and path.is_dir()
        and all((path / name).is_file() for name in checkpoint_files)
    ]
    checkpoint = max(complete, key=lambda path: int(path.name.removeprefix("checkpoint-")), default=None)
    if resume and not checkpoint:
        raise ValueError("no resumable checkpoint in the output directory")
    if not resume and output.is_dir() and any(output.iterdir()):
        raise ValueError("output directory has an existing run. Use --resume or a new directory")
    set_seed(config["seed"])
    rng = random.Random(config["seed"])
    train_rows, validation_rows = (read_rows(dataset / f"{split}.jsonl") for split in ("train", "validation"))
    rng.shuffle(train_rows)
    rng.shuffle(validation_rows)
    train_rows = train_rows[:config["train_limit"] or None]
    validation_rows = validation_rows[:config["validation_limit"] or None]
    if not train_rows or not validation_rows:
        raise ValueError("training and validation splits must be nonempty")

    source_commit = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=Path(__file__).resolve().parent,
        text=True, capture_output=True, check=False,
    ).stdout.strip() or None
    metadata = {
        "model": MODEL_ID,
        "revision": MODEL_REVISION,
        "source_commit": source_commit,
        "dataset_sha256": {
            split: hashlib.sha256((dataset / f"{split}.jsonl").read_bytes()).hexdigest()
            for split in ("train", "validation")
        },
        "config": config,
        "lora": {"r": 8, "alpha": 16, "dropout": 0.05, "target_modules": ["q_proj", "v_proj"]},
        "train_rows": len(train_rows),
        "validation_rows": len(validation_rows),
        "dependencies": {package: version(package) for package in (
            "torch", "transformers", "datasets", "trl", "peft", "accelerate", "tensorboard"
        )},
        "device": torch.cuda.get_device_name() if torch.cuda.is_available() else "cpu",
    }
    metadata_path = output / "run.json"
    if resume and json.loads(metadata_path.read_text(encoding="utf-8")) != metadata:
        raise ValueError("checkpoint configuration, environment or training data changed")
    output.mkdir(parents=True, exist_ok=True)
    metadata_path.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    LOG.info("Run configuration: %s", metadata)
    model = load_base(full_precision=True)
    model.config.use_cache = False
    trainer = make_trainer(model, load_tokenizer(), train_rows, validation_rows, config, output)
    trainer.model.print_trainable_parameters()
    trainer.train(resume_from_checkpoint=str(checkpoint) if resume else None)
    validation = trainer.evaluate()
    trainer.save_model(str(output / "adapter"))
    summary = {
        "best_checkpoint": trainer.state.best_model_checkpoint,
        "best_validation_loss": trainer.state.best_metric,
        "validation_metrics": validation,
        "history": trainer.state.log_history,
        "adapter": str(output / "adapter"),
    }
    (output / "training-metrics.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    LOG.info("Adapter: %s, selected validation loss: %s", output / "adapter", validation["eval_loss"])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path)
    parser.add_argument("config", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    train(args.dataset, args.config, args.output, args.resume)


if __name__ == "__main__":
    main()
