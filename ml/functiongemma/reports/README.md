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

The official tokenizer and weights require Hugging Face approval. The unauthenticated pinned download returned HTTP 401. Base and fine-tuned FunctionGemma scores, custom LiteRT-LM conversion and Android inference are pending.
