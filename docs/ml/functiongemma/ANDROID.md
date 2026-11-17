# FunctionGemma Android experiment

This is a local, experimental command interpretation path. The existing Italian parser remains the
production default. `VoiceCommandCoordinator` accepts an optional suspend interpreter; the FunctionGemma
adapter can return a validated `VoicePlan` to the coordinator's existing cancellation-aware executor.
`FunctionGemmaVoiceCommandInterpreter` itself only validates structured calls. The standalone dry-run
wrapper invokes an injected model adapter and traces the transcript, returned calls, and normalized
interpretation plus monotonic inference duration in memory. It has no playback dispatcher and does
not pause playback or capture a wake command.

## Export a merged Hugging Face checkpoint

Start with a merged Hugging Face directory containing the model config, safetensors weights, the
matching tokenizer files (`tokenizer.json` and preferably `tokenizer.model`), and the model's
FunctionGemma chat template. `litert-torch export_hf` accepts a local model directory as well as a
Hub identifier and packages LiteRT-LM bundles. The following is the current released-toolchain
candidate, not yet verified against Loopo's custom merged checkpoint:

```sh
python -m venv .venv
. .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install litert-torch==0.10.0

litert-torch export_hf \
  --model=/path/to/merged-functiongemma-hf \
  --output_dir=out/functiongemma \
  --task=text_generation \
  --bundle_litert_lm=True \
  --externalize_embedder=True \
  --single_token_embedder=True \
  --prefill_lengths=128 \
  --cache_length=1024 \
  --quantization_recipe=dynamic_wi8_afp32
```

Inspect the bundle with Google's [LiteRT-LM builder](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/docs/litert_lm_builder.md).
Use the produced bundle path in these commands.

```sh
python -m pip install litert-lm-builder==0.18.0
litert-lm-builder unpack --input /path/to/model.litertlm --output out/unpacked
```

Before using the output, confirm its extracted model metadata declares `function_gemma`, its start token
matches the checkpoint (Google's stock checkpoint uses token id 2), and its stop tokens include
`<end_of_turn>` and `<start_function_response>`. `<end_function_call>` closes the generated tool-call
block; `<start_function_response>` is the additional inference stop sequence. Load the bundle with
LiteRT-LM and compare prompts
rendered by the merged checkpoint's template to prompts rendered by the Android runtime. FunctionGemma
tool declarations have had template-versus-runtime formatting differences, so a successful export
alone does not establish correct tool prompting. Run the same paired literal and paraphrase routing
set on CPU and on each target phone; do not treat generation speed or a correct tool name as evidence
that its arguments are valid.

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

The command is based on Google's `export_hf` API and the released `litert-torch` 0.10.0 package. There
is no official, pinned recipe that has been verified specifically with an arbitrary merged
FunctionGemma checkpoint. If 0.10.0 fails or emits the wrong model type/template, preserve the
conversion log and inspect the HF `config.json`, template and exporter metadata builder before
trying another release. Do not silently ship a bundle tagged as a generic model.

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
adb push model.litertlm /data/local/tmp/model.litertlm
adb shell run-as com.p33nah.loopo.functiongemma sh -c \
  'mkdir -p files/functiongemma && cp /data/local/tmp/model.litertlm files/functiongemma/model.litertlm'
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
