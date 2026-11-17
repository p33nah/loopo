# FunctionGemma Android experiment

This is a local, experimental command interpretation path. The existing Italian parser remains the
production default. `VoiceCommandCoordinator` accepts an optional suspend interpreter; the FunctionGemma
adapter can return a validated `VoicePlan` to the coordinator's existing cancellation-aware executor.
`FunctionGemmaVoiceCommandInterpreter` itself only validates structured calls. The standalone dry-run
wrapper invokes an injected model adapter and traces the transcript, returned calls, and normalized
interpretation plus monotonic inference duration in memory. It has no playback dispatcher and does
not pause playback or capture a wake command.

## Export a merged Hugging Face checkpoint

Use `python -m loopo_functiongemma.model merge <adapter-dir> <merged-dir>` in the experiment
environment with access to the pinned base model. The merge saves the weights, tokenizer, and
FunctionGemma chat template. Run the export in a separate Python 3.13 environment. This combination
converted the `rejection-02` checkpoint on Colab using CPU. LiteRT Torch imports TensorFlow during
conversion. Builder 0.18.0 supplies the `TfLiteModelType` API required by this exporter.

```sh
python -m pip install torch==2.10.0 torchvision==0.25.0 \
  --index-url https://download.pytorch.org/whl/cpu
python -m pip install litert-torch==0.10.0 litert-lm-builder==0.18.0 tensorflow-cpu==2.21.0

cat > functiongemma.textproto <<'PROTO'
start_token { token_ids { ids: 2 } }
stop_tokens { token_str: "<end_of_turn>" }
stop_tokens { token_str: "<start_function_response>" }
llm_model_type { function_gemma {} }
PROTO

litert-torch export_hf \
  --model=/path/to/merged-functiongemma-hf \
  --output_dir=out/functiongemma \
  --task=text_generation \
  --bundle_litert_lm=True \
  --externalize_embedder=True \
  --single_token_embedder=True \
  --prefill_lengths=128 \
  --cache_length=1024 \
  --quantization_recipe=dynamic_wi8_afp32 \
  --use_jinja_template=False \
  --litert_lm_model_type_override=function_gemma \
  --litert_lm_llm_metadata_override=functiongemma.textproto \
  --tokenizer_path_override=/path/to/merged-functiongemma-hf/tokenizer.model
```

Check the bundle with Google's [LiteRT-LM builder](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/docs/litert_lm_builder.md).

```sh
litert-lm-builder unpack --input out/functiongemma/model.litertlm --output out/unpacked
```

The pilot uses LiteRT-LM's native FunctionGemma formatter. Prompt parity with Hugging Face needs
separate validation because the [two tool declaration renderers differ](https://github.com/google-ai-edge/LiteRT-LM/issues/3638).

## FunctionGemma wire shape

Google's public FunctionGemma guide shows strings escaped in the FC notation, for example
`location:<escape>Tokyo, Japan<escape>`. LiteRT-LM's native FunctionGemma formatter emits JSON strings
with `<escape>` delimiters but emits numbers and booleans as bare scalar text. An integer rate call
therefore has the form:

```text
<start_function_call>call:adjust_rate{delta_percent:-5}<end_function_call>
```

The minus sign is part of the bare numeric token. Google's public example parser converts unescaped
scalar text with Python `int()` first, which accepts a signed decimal integer; the LiteRT-LM parser
then returns the typed argument map in `Message.toolCalls`. This establishes the protocol shape, but
the custom fine-tune still needs a routing/argument evaluation to show it learned to emit it. The
FunctionGemma documentation identifies `<start_function_response>` as the extra inference stop marker;
it is not interchangeable with the call delimiter `<end_function_call>`.

## Kotlin structured result boundary

LiteRT-LM's Kotlin API supports `ConversationConfig(automaticToolCalling = false)`. Register the
tool schema, read `Message.toolCalls`, then convert each `(name, arguments)` pair to
`VoiceModelToolCall` and pass the complete list to `FunctionGemmaVoiceCommandInterpreter`. The API
also supports returning tool responses to the conversation using `Content.ToolResponse` and
`Message.tool(...)`. Loopo's experiment stops at interpretation: it deliberately does not send a
response back to the model or execute an action.

The adapter boundary is intentionally small:

```kotlin
val reply = conversation.sendMessage(transcript)
val calls = reply.toolCalls.map { VoiceModelToolCall(it.name, it.arguments) }
val interpretation = FunctionGemmaVoiceCommandInterpreter.interpret(calls)
```

For a standalone experiment, supply the model operation to `FunctionGemmaVoiceDryRun`; its callback
receives an in-memory `VoiceInterpreterTrace` with the input transcript, structured calls, normalized
accepted/rejected result, and request duration measured with `System.nanoTime()`. The timer covers
only the injected request. The harness does not persist or log it. The `VoiceCommandCoordinator`
also has an optional `interpret` seam; its default delegates to `ItalianVoiceCommandParser.parse`.
An opt-in adapter may return the accepted plan there, and the coordinator will re-check coroutine
cancellation and session currency before dispatch. A non-cancellation exception from the adapter
becomes `VoiceStatus.Failed`; coroutine cancellation propagates and dispatches nothing. That integrated
route retains the existing pause-before-transcription behavior; the standalone dry run avoids the
coordinator and its pause side effect.

Keep tool definitions aligned with the interpreter:

| Tool | Arguments |
| --- | --- |
| `resume` | none |
| `restart` | none |
| `stay_paused` | none |
| `set_rate` | integer `percent` in 25–200 |
| `adjust_rate` | nonzero integer `delta_percent` in −175–175 |

The interpreter rejects unknown tools, extra or missing fields, more than two calls, numeric strings,
fractions, values outside the supported rate limits, and action sequences the existing `VoicePlan`
does not permit.
The model can suggest a plan; it cannot dispatch playback. The coordinator's existing cancellation
checks guard every dispatch in the optional interpreter path.

The project targets minSdk 26 and packages `armeabi-v7a`, `arm64-v8a`, and `x86_64`. The opt-in pilot
uses `litertlm-android:0.18.0` with the project's Kotlin 2.4.21 compiler. It packaged and passed the
APK ELF/ZIP alignment verifier for the app's three ABI filters; the LiteRT-LM JNI library is present
for ARM64 and x86_64 but not ARMv7. The pilot target is the ARM64 Redmi; do not treat that build as
ARMv7 inference evidence.

## Redmi Note 14 Pro 4G offline pilot

The debug-only manual pilot is enabled with `-PloopoFunctionGemmaPilot=true`. It creates a separate
`com.p33nah.loopo.functiongemma` installation and launcher entry, and adds the pinned
`litertlm-android:0.18.0` runtime only to that variant. The pilot-only manifest removes INTERNET;
ordinary debug and release variants retain their normal manifest and application ID. The activity
keeps one CPU engine while open and creates one `Conversation` per request with
`automaticToolCalling=false`. It records typed or Vosk-transcribed input, the Kotlin parser plan,
raw text and structured tool calls, validated model plan or rejection, and monotonic request time.
History is visible/selectable and held only in activity memory. It never sends a plan to playback.

Build from WSL with the existing Italian model archive so speech works on first launch offline:

```sh
bash gradlew :app:assembleDebug -PloopoFunctionGemmaPilot=true \
  -PloopoWakeModel=/mnt/c/path/to/vosk-model-small-it-0.22.zip --console=plain
```

Install the resulting `app/build/outputs/apk/debug/app-debug.apk` beside Loopo. Stage the converted
bundle at app-private `files/functiongemma/model.litertlm` (model bundles stay outside Git):

```sh
adb shell run-as com.p33nah.loopo.functiongemma mkdir -p files/functiongemma
adb exec-in "run-as com.p33nah.loopo.functiongemma sh -c 'cat > files/functiongemma/model.litertlm.part'" < model.litertlm
adb shell run-as com.p33nah.loopo.functiongemma sha256sum files/functiongemma/model.litertlm.part
```

Compare the printed hash with the model you exported. If it matches, rename the staged file and run a
typed command.

```sh
adb shell run-as com.p33nah.loopo.functiongemma mv files/functiongemma/model.litertlm.part files/functiongemma/model.litertlm
adb shell am start -n \
  com.p33nah.loopo.functiongemma/com.p33nah.loopo.ui.voice.FunctionGemmaPilotActivity \
  --es com.p33nah.loopo.functiongemma.TRANSCRIPT 'aumenta la velocità a ottanta'
```

The explicit `TRANSCRIPT` extra runs one typed phrase for repeatable checks. For live ASR, tap
**Tocca e parla**, grant microphone access, and say one command. Select/copy the visible numbered
history or inspect it with Android UI text dumps. No transcript or audio is written to storage or
logcat. Record model/APK hashes, Android version, build fingerprint, ABI, and routing outcomes outside
the app. Test refusal, invalid/out-of-range rates, literal requests, and paraphrases. Compare parser
and model proposals; do not treat a correct tool name as an accepted plan unless Kotlin validation
accepts its full ordered arguments. This harness does not test playback dispatch, wake detection, model
export parity, or acoustic accuracy on other manufacturers.

## Redmi results on 2026-11-17

The ARM64 Redmi Note 14 Pro 4G ran Android 15 (API 35) with a 4 KB page size. The side-by-side APK
had SHA-256 `8f82c490e07065c7eb792583b3d08598bae8520f1e1561af8e2097c0fb09ffc0` and lacked
INTERNET permission. The 452,594,560-byte `rejection-02` INT8 bundle had SHA-256
`5170cd1e6684dbbdd9fc84e37d1783b74661313eb579c45f4a005d76ab06f7cc` on both Colab and the phone.

After a cold launch, a typed `riprendi` returned `resume` in 2,211 ms. The live Vosk trial recorded
two accepted restart plans and three rejected rate plans. The original Kotlin boundary rejected the rate calls because it did not recognize the Gson
`Number` representation from LiteRT-LM. The trace also rendered those values as null. The parser
produced a usable plan on all five transcripts. The model requests took 1,711–2,309 ms, measured around
`sendMessage` only. The trial establishes offline speech-to-plan execution on this phone. It leaves
rate argument generation or decoding, wake-word activation, and playback dispatch unverified.

The corrected pilot APK had SHA-256
`5de7a5e93187c4cbeeb908ba383874045474b9d6f6e790a071c3d5485355af07`. I replayed the three rate
transcripts as text on the same Redmi with the same bundle. All three calls had valid numeric
arguments. The model got one plan exactly right (−7 points). It returned an absolute 71% instead
of 70% for one transcript and proposed a relative −90 points instead of an absolute 70% for
another. A separate typed negation produced a malformed call in the response text and no structured
call. The validator returned no plan. The four cold-launch inference calls took 2,663–3,283 ms.
The corrected build passed the exact-integer regression, rejecting fractional, nonfinite and
overflowing values.
