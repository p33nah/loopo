# M5 time stretch status

- [x] Inspect the existing decoder, producer, JNI, Oboe callback, loop and frame-mapping contracts.
- [x] Compare callback DSP, the existing producer, and a separate DSP worker. Keep stretching in the existing producer so the real-time callback remains small and the pipeline has one writer.
- [x] Select the permissive engine path requested by the user. Integrate Signalsmith Stretch and include MIT notices.
- [x] Implement bounded 25–200% time stretch, preroll, end drain, source-frame mapping, loop wrapping, and underrun backpressure.
- [x] Replace fixed speed choices with a linear 1% slider and one-tap 100% reset.
- [x] Find and fix the 0.25x busy-spin and the MP3 early-EOF loop-preroll failure on the Redmi.
- [x] Build debug and final release APKs; install debug on the connected Redmi and confirm the final transport layout and percentage persistence.
- [x] Run a 10-minute 2x full-track loop test: zero underruns and three boundary crossings.
- [x] Omit another 10-minute continuous 0.25x run at the user's request; six continuous minutes and a separate two-minute run showed zero underruns before the user-paused interval.
- [x] Verify 95% and 96% selections on-device; adjust the continuous slider presentation and transport height after screenshot review. Recheck the final layout after rebuild.
- [ ] Optionally test device playback at 0.50x, 0.75x and 1.50x. No long stress run is planned unless requested.
- [ ] Investigate the implausible ~96-second Oboe latency estimate observed after a loop reset. Ordinary playback showed 86–98 ms.
- [x] Review the full diff and build the final release variant.
- [ ] Open a PR for the user to merge.

The decoder position, stretcher delay, output ring and source-frame tags form one ordered stream, so this branch has one implementation owner.
