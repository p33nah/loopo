# Benchmark methodology

Run all methods on the same frozen `data/test.jsonl` and `data/challenge.jsonl` bytes. Evaluate the current production parser and the expanded PR #67 centroid against greedy, one-turn base FunctionGemma and the LoRA adapter selected by validation loss. The parser and classifier run in the JVM replay. The classifier uses the pinned source and training assets from [PR #67](https://github.com/p33nah/loopo/pull/67). Its older action vocabulary lacks restart. Its source stays in the temporary replay build. Use the [reproduction commands](../README.md#local-training-and-comparison) to regenerate machine results.

Score normalized ordered tool names with signed integer arguments. A compound is correct only if every action and slot matches in order. The arguments metric measures rows with required slots after tool sequence selection succeeds. Tool precision and recall count each tool once per row. Confusion counts use ordered tool sequences, including no-call and invalid responses. Unseen paraphrases, synthetic ASR text and observed or reconstructed phone text have their own variant breakdowns.

A completed plain-text no-call can be correct on a negative. A malformed, unknown, out-of-range or truncated output scores invalid, even when application validation blocks it from dispatch. A valid nonempty result for a negative is a false acceptance. A false acceptance or a valid wrong plan on a positive contributes to dispatch risk. This is a count of proposals that pass static validation, before the player resolves a relative rate or applies session state.

Record results separately by split. JSON includes denominators, per-category and variant counts, precision/recall, timing and per-row data. CSV provides scored rows and a long-form confusion matrix. The Markdown report shows summary counts and representative failures, with potentially dispatchable wrong plans first.

The timing columns describe warm transcript interpretation. The Kotlin and model measurements come from their own runtime and hardware. GPU results include tokenization, greedy generation, and native decoding/validation. Mean, median and nearest-rank p95 are in milliseconds. Parameter bytes and, when measurable, CUDA allocator peak are recorded separately. These footprints do not measure process RSS, audio latency or battery. A fair on-device performance comparison uses the same Redmi, fixed thread/backend settings, repeated interleaved replay and cold/warm runs. Record wake-to-transcript, transcript-to-plan and transcript-to-action separately. A live capture is needed for Vosk-to-action measurements.

## Baseline replay results

Both Kotlin baselines ran in a WSL Ubuntu 24.04 JVM on an Intel Core i5-7300U. The parser source is from `43f2ffc`. The classifier uses 169 original and expanded training rows from `9465326`. No replay transcript exactly matched a classifier training phrase after normalization. These are corpus counts. Template families make the rows statistically dependent.

| Split | Interpreter | Exact plans or rejections | Correct positive plans | Valid wrong plans | False accepts |
| --- | --- | ---: | ---: | ---: | ---: |
| Test | Production parser | 47/89 | 35/77 | 0 | 0/12 |
| Test | Expanded classifier | 45/89 | 33/77 | 3 | 0/12 |
| Challenge | Production parser | 26/32 | 0/6 | 0 | 0/26 |
| Challenge | Expanded classifier | 25/32 | 0/6 | 1 | 1/26 |

The parser matched two of nine test compounds. The classifier matched one. It resumed on two restart requests and on a request to stay paused. On challenge text it treated “aumenta al 50 percento” as an absolute rate, and it resumed where the reconstructed Redmi transcript required a restart after a rate change.

Each baseline made three untimed warmup passes followed by one timed JVM interpretation per row. The report includes mean, p50 and p95 for each split. The timings are descriptive of that run and the JVM host was shared. Performance ranking and Redmi latency need repeated on-device measurements.

The test input SHA-256 is `44b2c5ace0d67db010461a8dcbbf76d43ec26af5036d3e9e672bfde385bf1d61`. The challenge input SHA-256 is `ddb83b388ef28f784e6bf0b865764c1209a739b7b9070e60adb28ac7bcbe14ba`. Reproduce the report with the replay and comparison commands in the [experiment README](../README.md#local-training-and-comparison).

## Colab seed and balanced follow-up

The Colab T4 run trained for three epochs on the 380-example seed. The selected checkpoint was checkpoint-144. The base model matched 53/89 test rows and 2/32 challenge rows. The tuned model matched 62/89 test rows and 5/32 challenge rows. It also proposed 32 valid but wrong or unwanted plans across both sets, compared with zero for the production parser. See the [PR evidence](https://github.com/p33nah/loopo/pull/73) for the run details.

The balanced run used 3,000 training examples, including 1,200 no-call examples. Its validation split had 134 rows and 43 no-call examples. The test and challenge bytes matched the Kotlin and Colab seed replay. The selected checkpoint was checkpoint-1125. It matched 70/89 test rows and 8/32 challenge rows. Its 14 wrong or unwanted challenge plans included 12 calls on the 26 negative rows. Custom LiteRT-LM conversion and Android inference are pending.

## Targeted rejection follow-up

The [additional training phrases](../examples/rejection-followup.txt) cover the main error classes in the balanced challenge replay, including missing values, alternatives, negation, absent playback controls, and unsupported sequences. The file has 51 no-call phrases and 20 supported commands. Generate the training split from the `ml/functiongemma` directory and keep the standard training configuration.

```sh
python -m loopo_functiongemma.corpus generate runs/rejection-data \
  --examples examples/rejection-followup.txt
python -m loopo_functiongemma.model validate-format runs/rejection-data
python -m loopo_functiongemma.train runs/rejection-data configs/train.toml runs/rejection-training
```

For the follow-up, use the original challenge as a regression set. The [48 new transcripts](rejection-probe.txt) were frozen before the corrected run and kept out of training and checkpoint selection. Of these, 32 should produce no call and 16 should produce an exact plan. These curated rows measure the targeted behavior and do not estimate its frequency in live speech.

```sh
python - <<'PY'
from pathlib import Path

from loopo_functiongemma.corpus import read_examples
from loopo_functiongemma.dataset import _write_jsonl

rows = read_examples(Path("reports/rejection-probe.txt"), "rejection_probe")
_write_jsonl(Path("runs/rejection-probe.jsonl"), rows)
PY
```

The probe JSONL has SHA-256 `d51d261b3f72c63cc712db190be9102f6ef7197e3bb89e56d4a398ac13a6fd35`. Use the [replay and comparison commands](../README.md#local-training-and-comparison) on that file for the parser and both adapters. The production parser rejected 32/32 negatives and matched 3/16 positive plans. The PR #67 classifier rejected 31/32 negatives and matched 8/16 positive plans, with one valid wrong plan on a positive. See the [PR evidence](https://github.com/p33nah/loopo/pull/73) for the adapter comparison.
## Contrastive corpus audit

The November 18, 2026 audit regenerated the 3,000-row `rejection-02` corpus before changing the generator. The new `contrastive` profile keeps the row count and the 1,200 no-call rows. The [generation command](../README.md#run-the-contrastive-follow-up) includes the prior rejection contribution and protects both evaluation files.

| Training coverage | `rejection-02` | `contrastive` |
| --- | ---: | ---: |
| Absolute values represented at least once | 120/176 | 176/176 |
| Absolute values represented in digits and words | 17/176 | 176/176 |
| Signed deltas represented at least once | 178/350 | 350/350 |
| Signed deltas represented in digits and words | 19/350 | 350/350 |
| Generated context wrappers | 1,698 | 598 |

The audit counts the first action of compound plans. The old generator used digits for even numeric expansions and words for odd expansions. The new generator uses both forms for every supported value. It includes absolute requests without a unit and absolute and relative requests sharing the same percentage unit. It varies negation, ambiguous choices, and values just inside or outside the supported range. The authored-label review changed three pure prohibitions from pause actions to no-call and dropped three weak transport examples in the new profile. Automated checks cover every generated row's structure, expected plan, numeric coverage, and overlap with evaluation templates.

The test, challenge, and validation splits retain their historical bytes. The prior rejection probe, the earlier numeric replay, and 32 newly authored held-out examples run after checkpoint selection. The new probe includes natural paraphrases and one simulated recognition error. Run `evaluate compare` separately for each set so the results retain their denominators. The audit establishes dataset coverage. Model accuracy requires the GPU run.
