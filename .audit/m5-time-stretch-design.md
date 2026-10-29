# M5 time stretch: design and verification

## Design

Signalsmith Stretch is integrated behind `TimeStretcher`. It is MIT licensed, so the app can keep its current distribution terms; Rubber Band's GPL/commercial choice is avoided. The source snapshots and notices record the upstream revisions.

`AndroidPlaybackController.pumpDecoder` is the sole producer. It decodes PCM, supplies preroll, asks the native `TimeStretcher` to produce a rate-dependent number of frames, tags the output with source-frame positions, then writes to the existing ring. The Oboe callback only consumes prepared frames. Seeking and loop changes reset the stretcher and prime it again.

Playback speed is represented as an integer percentage in the validated range 25–200. The transport slider moves linearly in 1% steps, displays both percent and multiplier, and commits once when the user releases it. A one-tap `1x` control restores 100%.

If an enabled loop reaches decoder EOF just before its metadata B frame, preroll and exact loop reads seek back to A and continue. This handles MP3 duration metadata that can overstate the decodable frame count. The producer also waits when there is too little ring capacity to generate even one output frame, instead of spinning at full CPU.

## Device evidence

Target: Redmi Note 14 Pro 4G, Android, built and installed over USB.

- The first 0.25x playback exposed a producer busy-spin. The app reached 185% of one core and underruns accumulated. After adding backpressure, the debug build played at 0.25x for 12 seconds with zero underruns; the user reported the audio was regular.
- A release build played the 7:45 MP3 at 2x with loop enabled for 10 minutes: zero underruns, three loop-boundary crossings, and user-reported regular audio. `top` sampled 100–146% of one core (the device reports eight cores, so roughly 12.5–18.3% of total CPU capacity).
- At 0.25x, the release build played for six minutes with zero underruns. The user paused it manually. After resuming, a further two minutes showed zero underruns. This was not one continuous 10-minute run; the user asked to omit that long test.
- The first 0.25x loop run failed near B because the MP3 decoder returned EOF at frame 20,525,183 while metadata declared B at frame 20,525,184. The loop-aware preroll and exact-read handling now wrap to A on that early EOF. A short near-B device run then wrapped without a decode error; the underrun count rose by two at restart and stayed stable afterward, while the user reported regular audio.
- A 440 Hz synthetic stereo fixture was processed on-device in five alternating runs at both extremes. Pitch measured +4.95 to +6.23 cents at 0.25x and -0.98 cents at 2x, within the 20-cent criterion. This was a native harness, not a measurement from the full app.
- The full app's sampled CPU at 0.25x was 114–128% of one core in release and 2x was 100–146%. The release build's standalone sine harness used about 3.2–3.4% of one core; this is a different workload and should not be compared directly to the MP3 app reading.
- The on-device transport slider selected both 95% and 96%, displayed the matching `0.95x`/`0.96x` values, and played with zero underruns during the short interaction check. A screenshot exposed crowded tick marks and a clipped status row; the slider is now rendered continuously, snaps to integer percentages on release, and the transport card is taller. Verify this final layout after the latest build.

## Remaining verification limits

The user asked to avoid repeated 10-minute tests. The 2x loop test reached 10 minutes; the final 0.25x build has a six-minute continuous run plus a separate two-minute run. The 10-minute 0.25x run is intentionally omitted. Rates 0.50x, 0.75x and 1.50x have not had device stress runs. Pitch was measured only with a sine fixture, and audio quality was checked on the user's Gregorian chant MP3. During one full-loop run the app displayed an implausible Oboe latency estimate near 96 seconds; ordinary playback showed 86–98 ms. Later short checks displayed 229–771 ms, so the estimate anomaly remains a separate follow-up before treating latency reporting as verified.
