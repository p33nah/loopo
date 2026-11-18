# Italian FunctionGemma experiment

Can FunctionGemma 270M recover Italian paraphrases and Vosk transcription errors while keeping unintended player actions low enough for hands-free use? Compare the current parser, the experimental expanded classifier, base FunctionGemma and a small LoRA fine-tune on the same transcripts. The [voice architecture and tool contract](ARCHITECTURE.md) describe the five supported voice actions. [Benchmark methodology](reports/README.md) defines the measurements.

## Inspect and validate the corpus

Run these commands from `ml/functiongemma` with Python 3.11–3.13:

```sh
python -m venv .venv
. .venv/bin/activate
python -m pip install -e '.[test]'
python -m loopo_functiongemma.dataset validate data
python -m loopo_functiongemma.dataset generate generated/seed
python -m loopo_functiongemma.dataset validate generated/seed
python -m pytest -q
```

On Windows PowerShell activate with `.venv\Scripts\Activate.ps1`. The checked-in seed has 380 training rows, 66 validation rows and 89 test rows, plus 32 challenge rows. Each row carries its transcript, ordered expected calls, family, category, variant and source. Human-authored phrase families combine with bounded numeric slots. Authored examples cover spelled-out numbers, colloquial requests, synthetic recognition errors and ambiguous, out-of-range, unsupported or unrelated requests. Any real or reconstructed Vosk examples are marked with their source. The challenge set is held out alongside the test set.

The generator assigns a parameterized family to one split. Validation checks schema, typed plans, unique IDs, normalized text, contradictory labels and normalized numeric template shapes across splits. Held-out replay uses the checked-in files. Keep them fixed after the first full experiment. Use `--expand-train 2500` when generating an expanded corpus. It adds unique numeric variants to training with seed 67 and leaves the validation, test and challenge bytes fixed. Expansion increases numeric coverage and reduces the proportion of no-call rows. Compare rejection scores with the seed experiment before choosing this corpus.

## Expand the corpus and add your own phrases

The seed run proposed valid but unwanted actions for 11 of 26 negative challenge transcripts. Its 380 training rows have just 22 rejection examples. The old numeric expansion keeps those same 22 even at 2,500 rows. The balanced profile adds 328 authored training phrases and 68 authored validation phrases. It generates variants of the training families to reach the requested size and samples each family in turn within its category.

| Split | Original rows | Original rejections | Balanced rows | Balanced rejections |
| --- | ---: | ---: | ---: | ---: |
| Training | 380 | 22 | 3,000 | 1,200 |
| Validation | 66 | 5 | 134 | 43 |
| Test | 89 | 12 | 89 | 12 |
| Challenge | 32 | 26 | 32 | 26 |

Training includes resume, restart, pause, absolute and relative rates, and rate changes followed by resume or restart. Rejections cover negation, ambiguity, unsupported requests, invalid values, and unrelated conversation. The added validation phrases use independent families. Both test files retain their original bytes and hashes. Review generated context and numeric variants as synthetic training data.

From the project directory, generate or inspect the corpus with Python:

```sh
python -m loopo_functiongemma.corpus generate generated/balanced-v1
python -m loopo_functiongemma.corpus inspect generated/balanced-v1
```

Use `--rows 5000` for the larger profile. The default rejection share is 40%. Use `--reject-percent` to try another share with enough examples on each side. This percentage is an experiment setting.

To add your own phrases, copy [le-mie-frasi.txt](examples/le-mie-frasi.txt). Write one transcript per line as `label | family | phrase`. Reuse the family name for paraphrases and variants of the same template.

```text
set_rate:64 | velocita_studio | Per questa prova dammi una velocità pari al 64 per cento.
adjust_rate:-8+restart | diminuisci_e_riparti | Leva otto punti di velocità e riparti subito dall'attacco.
reject:negated | non_toccare | Evita di far ripartire la registrazione ora che è ferma.
```

The other action labels are `resume`, `restart`, and `stay_paused`. Use `+` to append `resume` or `restart` after a rate change. Rejection reasons are `ambiguous`, `negated`, `out_of_range`, `unrelated`, and `unsupported`. Use short family names with lowercase ASCII letters, numbers, underscores, or hyphens.

```sh
python -m loopo_functiongemma.corpus generate generated/contributi --examples examples/le-mie-frasi.txt
python -m loopo_functiongemma.corpus import generated/personale --examples examples/le-mie-frasi.txt
```

`generate` adds your annotated examples to the balanced corpus before sampling. `import` uses your examples as the entire training split and keeps the original evaluation splits. Repeat `--examples` to read several files. The importer reports invalid labels, normalized duplicates, and numeric templates shared with evaluation. Human review is still needed for semantic duplicates.

To add personal examples, upload your text file to Colab and set `examples_file` to its runtime path, such as `/content/le-mie-frasi.txt`.

## Run the contrastive follow-up

The `contrastive` profile trains on paired phrasing for absolute rates, signed changes, and negated requests. Each supported absolute rate and signed delta appears in digits and words. The historic test and challenge files stay byte-identical. The prior numeric and rejection probes, plus the new [32-case evaluation set](reports/contrastive-holdout.txt), stay out of training and checkpoint selection.

Generate and inspect the same input files before using a GPU. Run from `ml/functiongemma`.

```sh
python -m loopo_functiongemma.corpus generate generated/contrastive-v1 \
  --profile contrastive \
  --examples examples/rejection-followup.txt \
  --holdout reports/rejection-probe.txt \
  --holdout reports/contrastive-holdout.txt
python -m loopo_functiongemma.corpus inspect generated/contrastive-v1
```

In the notebook, select **T4 GPU**. Set `repo_ref` to the full commit SHA, `experiment` to `contrastive`, `use_drive` to `True`, and `run_name` to a fresh name. Run the cells in order. The notebook verifies the Drive mount and format of every training target before training. For a fresh checkout, use the same commit in the notebook URL and `repo_ref`.

The new profile retains every epoch checkpoint and a recovery checkpoint every 125 optimizer steps. Checkpoint selection uses generated plans on validation. It counts wrong or unwanted valid plans twice and other mismatches once. Ties prefer fewer wrong or unwanted plans, then the earlier checkpoint. Training also saves the loss-selected adapter for comparison. The notebook exports full-precision weights from the generation-selected checkpoint and evaluates those weights separately.

The run directory is `MyDrive/loopo-functiongemma/contrastive/<run_name>`. It contains the corpus, copied configuration and notebook, package versions, input hashes, all checkpoints, the loss-selected adapter, the merged checkpoint, prediction files and reports. After an interruption, use the same SHA, GPU type and run name, enable `resume`, and run the cells again. The trainer resumes the latest complete checkpoint matching the run metadata. Keep the prior directory if no complete checkpoint exists, then use a fresh run name.

The sibling ZIP includes per-file checksums and a `.zip.sha256` sidecar. The notebook reopens and verifies it. Keep the Drive directory for resume and copy the verified ZIP and sidecar to separate storage. When you generate the [INT8 bundle](../../docs/ml/functiongemma/ANDROID.md) from `merged-hf`, retain the bundle and its replay evidence in the run directory and create a newly named archive.

```sh
python -m loopo_functiongemma.artifacts archive /path/to/run /path/to/run-with-int8.zip
python -m loopo_functiongemma.artifacts verify /path/to/run-with-int8.zip
```

## Run the targeted Redmi follow-up

The targeted run starts a new optimizer from the saved contrastive LoRA checkpoint. It uses only the 46 hand-labeled training phrases in `examples/redmi-targeted.txt`. They contrast explicit and negated commands, missing numbers, absolute rates and signed changes, supported and unsupported action order, and unrelated playback terms. Ten new validation phrases supplement the original validation split. Keep the frozen test, challenge, numeric replay, and targeted holdout out of checkpoint selection.

In Colab, use the notebook from the same Git commit, select a GPU, set `experiment = "targeted"`, enable `use_drive`, and use a fresh `run_name`. Set `initial_adapter_path` to the contrastive generation-selected checkpoint. The notebook copies the input adapter and its base revision record into the new run, verifies the output ZIP, and saves the merged weights. Use the same options and `resume = True` to continue a run with saved checkpoints. The full-precision notebook report is a diagnostic. Export the selected model to INT8 and replay the same frozen transcripts before using the result to judge Android deployment.

Run from `ml/functiongemma` in WSL. Install `litert-lm==0.18.0` into the replay environment alongside the project package.

```sh
python scripts/replay_baselines.py reports/targeted-holdout.jsonl --output-dir runs/targeted-parser
python scripts/replay_litert.py /path/to/parent.litertlm runs/targeted-parent reports/targeted-holdout.jsonl
python scripts/replay_litert.py /path/to/candidate.litertlm runs/targeted-candidate reports/targeted-holdout.jsonl
python -m loopo_functiongemma.evaluate compare reports/targeted-holdout.jsonl runs/targeted-comparison \
  --input parser=runs/targeted-parser/targeted-holdout.parser.jsonl \
  --input parent=runs/targeted-parent/targeted-holdout-litert.json \
  --input candidate=runs/targeted-candidate/targeted-holdout-litert.json \
  --gate-input candidate --min-positive-exact-rate 0.95 \
  --max-false-accept-rate 0.01 --max-wrong-usable-rate 0.01
```

A positive is exact when all actions, integer arguments, and their order match. The candidate needs at least 95% exact on valid commands. It may propose a valid action on at most 1% of negative requests and a valid wrong plan on at most 1% of positive requests. Send the same frozen transcripts to the Redmi pilot to verify the Android runtime separately. The pilot shows a second result that requires full parser agreement. Use the model result before that check to measure what FunctionGemma learned. The agreement check blocks unsupported or discrepant plans whenever the parser disagrees. It cannot extend parser coverage.

## Colab smoke and full run

1. Open the [notebook](notebooks/loopo_functiongemma_finetuning.ipynb) in Google Colab using **File → Open notebook → GitHub**. Choose this repository, the ref containing the notebook and the notebook path. You can also download its `.ipynb` file and upload it in the **Upload** tab.
2. Open the [FunctionGemma model page](https://huggingface.co/google/functiongemma-270m-it), sign in, accept the Gemma terms and request access. Create a read token with access to that gated repository. Add the token as **HF_TOKEN** in Colab **Secrets** and grant notebook access.
3. Choose **Runtime → Change runtime type → T4 GPU** or an available NVIDIA GPU. In the options cell use the same repository ref as step 1. Keep `experiment = "smoke"`, leave `resume` and `export_merged` off, and run all cells. A CPU runtime can exercise the same small smoke against the official model, with longer processing time.
4. Confirm format validation, base and tuned smoke reports, two optimizer steps, and a saved adapter and checkpoint. Inspect `training/run.json` and `training/training-metrics.json`. The smoke covers four validation transcripts for generation and uses six validation rows for loss. Its scores are a pipeline check.
5. Set `experiment = "seed"` and choose a fresh run name to run the first full comparison. Enable `use_drive` if you need checkpoints across Colab sessions. The output is under `/content/loopo-runs/<experiment>/<run_name>` or `MyDrive/loopo-functiongemma/<experiment>/<run_name>`. For interrupted training, use the same exact code ref, configuration, output and runtime type, then enable `resume`. A run interrupted before its first checkpoint needs a fresh run name.
6. Review training and validation loss by epoch. Later training loss improvements paired with rising validation loss suggest overfitting. The trainer selects the lowest validation-loss checkpoint. The rejection-focused follow-up uses `balanced` with a fresh run name and the same frozen test and challenge sets.

The notebook supports `base` to run the original model alone. The trained artifact stays on the chosen machine or Drive. The notebook does not publish weights or corpora.

## Local training and comparison

Use `python -m pip install -e '.[ml,test]'` in an isolated Linux environment. For a CPU smoke test, install `torch==2.10.0` first from the [CPU wheel index](https://download.pytorch.org/whl/cpu). Colab installs the same version from the [CUDA 12.8 wheel index](https://download.pytorch.org/whl/cu128) when a GPU is present.

Set `HF_TOKEN` in the process environment or authenticate with `hf auth login`. The following calls use the official tokenizer and pinned weights:

```sh
python -m loopo_functiongemma.model validate-format data
python -m loopo_functiongemma.train data configs/smoke.toml runs/smoke
python -m loopo_functiongemma.train data configs/train.toml runs/seed
python -m loopo_functiongemma.evaluate predict data/test.jsonl runs/base-test.json
python -m loopo_functiongemma.evaluate predict data/test.jsonl runs/tuned-test.json --adapter runs/seed/adapter
```

Run `train ... --resume` with the original parameters to resume the latest complete checkpoint. Supply the adapter directory with its sibling `run.json` when copying it to another machine. The trainer records model and source revisions, data fingerprints, selected dependency versions, device, hyperparameters, loss history and the selected checkpoint. LoRA trains ranks on query/value projections. Training loads full-precision weights and uses BF16 mixed precision when the GPU supports it. Training and inference use FP32 on T4 to avoid the narrower FP16 exponent range. Each batch has one example and the full experiment accumulates eight batches per update. The tokenizer renders both native conversations and inference prompts. The input and tool declarations are masked out of SFT loss. Two supported terminal markers stop greedy inference.

From the same project directory in the [documented Loopo WSL environment](../../docs/testing.md), replay the Kotlin baselines and join their outputs to the model results. Run the same commands with `challenge.jsonl` and separate output paths to score rejection and unusual phrasing cases.

```sh
python3 scripts/replay_baselines.py data/test.jsonl --output-dir runs/test-baselines
python -m loopo_functiongemma.evaluate compare data/test.jsonl runs/test-comparison \
  --input parser=runs/test-baselines/test.parser.jsonl \
  --input classifier=runs/test-baselines/test.classifier.jsonl \
  --input base=runs/base-test.json \
  --input tuned=runs/tuned-test.json
```

Keep the prediction file, metadata sidecar and raw input together. Comparison checks the input fingerprint and exact ID coverage. The report writes `report.json`, `predictions.csv`, `confusion.csv` and `summary.md`. To compare full Colab results locally, download the base and tuned JSON files and use their paths instead of the local model outputs. Leave missing interpreters out of the command while collecting evidence.

## Export and Android

```sh
python -m loopo_functiongemma.model merge runs/seed/adapter runs/seed/merged-hf
```

This prepares a full-precision Hugging Face checkpoint, tokenizer and provenance manifest. It merges the adapter into the base before external conversion. See the [LiteRT-LM candidate export and Redmi device procedure](../../docs/ml/functiongemma/ANDROID.md) for the isolated converter, quantization setting, metadata checks and Kotlin runtime contract.

## Reference versions

The experiment pins `google/functiongemma-270m-it` at `39eccb091651513a5dfb56892d3714c1b5b8276c`. The Python package pins PyTorch 2.10.0, Transformers 5.3.0, Datasets 4.5.0, TRL 0.29.0, PEFT 0.18.1, Accelerate 1.13.0, TensorBoard 2.20.0 and SentencePiece 0.2.1. The local CPU dependency and tiny-model SFT fixture checks use these pins. Colab T4 verified the official tokenizer round-trip, GPU smoke, and seed and balanced benchmarks. See the [replay results](reports/README.md) for the benchmarks and targeted rejection follow-up. Custom model conversion needs a separate run. The conversion candidate is LiteRT Torch 0.10.0 in its own environment.

Primary references checked on 2026-11-16.
[FunctionGemma overview](https://ai.google.dev/gemma/docs/functiongemma) ·
[fine-tuning guide](https://ai.google.dev/gemma/docs/functiongemma/finetuning-with-functiongemma) ·
[native formatting](https://ai.google.dev/gemma/docs/functiongemma/formatting-and-best-practices) ·
[full tool call sequence](https://ai.google.dev/gemma/docs/functiongemma/full-function-calling-sequence-with-functiongemma) ·
[pinned model](https://huggingface.co/google/functiongemma-270m-it/tree/39eccb091651513a5dfb56892d3714c1b5b8276c) ·
[Transformers 5.3 tokenizer API](https://huggingface.co/docs/transformers/v5.3.0/en/internal/tokenization_utils) ·
[TRL 0.29 SFT](https://huggingface.co/docs/trl/v0.29.0/en/sft_trainer) ·
[PEFT 0.18.1 LoRA](https://huggingface.co/docs/peft/v0.18.1/en/package_reference/lora) ·
[LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) ·
[Mobile Actions deployment reference](https://ai.google.dev/gemma/docs/mobile-actions).
