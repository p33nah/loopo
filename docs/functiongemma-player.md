# FunctionGemma playback experiment

The separate **Loopo FunctionGemma** app runs the current player and the real Vosk wake and command capture. After Vosk finalizes the transcript, the experiment submits it to LiteRT-LM 0.18.0 and validates the suggested tool calls as a Loopo voice plan. The normal voice coordinator owns cancellation, dispatch order and the decision to resume after a rate change. The training and dry-run benchmark are recorded in [PR #73](https://github.com/p33nah/loopo/pull/73).

## Build and install

Build in WSL with the Android toolchain in [Verify Loopo](testing.md). Pass the official Italian Vosk archive as documented there.

```sh
bash gradlew :app:assembleFunctionGemma -PloopoFunctionGemma=true \
  -PloopoWakeModel=/path/to/vosk-model-small-it-0.22.zip --console=plain
```

Install the experimental APK as `com.p33nah.loopo.functiongemma.player`. Its build type uses the debug key. The official release tasks still build `com.p33nah.loopo` with the current parser. Check the release APK for the absence of LiteRT-LM native libraries and FunctionGemma model files.

Open the experiment controls in the player and import the [targeted INT8 bundle](https://drive.google.com/file/d/1ohPdPx0FmNetZ9cD3LKhWEFU3xd9l9_4/view). The 452,594,560-byte file must match SHA-256 `b0164093abbc2adcf776c9012d75248f5534328e5569f63a64ee55870770a836`. The app stores it privately. Open a track, select CPU or GPU with voice disarmed, then arm voice on the wolf icon and grant microphone access. Wait for wake listening before saying **Ehi Lupo**. If GPU initialization fails, the app reports the failure so you can select CPU explicitly. [LiteRT-LM native GPU libraries](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/docs/api/kotlin/getting_started.md#2-initialize-the-engine)

Parser agreement is the default. The model can control the player in this mode when every action and argument matches the parser's ordered plan. Select model-only mode in the experiment to observe its independent behavior. This mode can apply an unwanted playback action or a wrong rate. Use a test track. Both modes validate tool names, plan order, and numeric limits.

## Compare CPU and GPU

Use the same phone, APK, bundle, track and agreement mode for both backends. Log the backend, the exact recognized transcript shown by Loopo, requested player change, observed player state, and rejected or failed turns. Run at least five warm turns per backend and keep the first cold initialization separate. Interleave backend runs when practical. Record thermal state if measuring differences over sustained use.

Say **Ehi Lupo** and wait for the listening cue before each test command. Try **imposta la velocità al settanta per cento**, **riprendi**, **ricomincia da capo**, **pausa**, and **non ricominciare**. Check the rate, play state, and restart position in the player. Repeat the same commands on both backends. Record test speech synthesis and live microphone speech separately. Retain transcripts and outcomes only.

The experiment reports time spent preparing the model separately from per-turn timing. Capture begins before Vosk starts the command recorder and includes endpointing. Interpretation ends when the validated plan is available. Dispatch ends when the player acknowledges the requested action sequence. Total time begins immediately after the wake detector invokes the command turn. It includes the pause and the stages that actually ran. The dispatch acknowledgment measures app state and does not measure audible response at the speaker.
