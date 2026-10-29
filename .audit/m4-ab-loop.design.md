# M4 A/B loop design

## Grounded flow

`StudyScreen` sends play and seek actions through `StudyViewModel` to `AndroidPlaybackController`. The controller owns one PCM decoder and a background pump. The pump writes PCM into `PcmRingBuffer` through `NativePlaybackBridge`; Oboe's callback drains the ring and advances a single linear `sourcePosition_`. The waveform canvas draws the playhead from `PlaybackSnapshot.positionFrame` and maps taps to source frames.

The current model already has `LoopRegion(aFrame, bFrame, enabled, crossfadeMs)`, but no loop state reaches the controller, native ring, or UI.

## Candidate shapes

### A. Producer-built loop segments with frame tags

The Kotlin pump reads normal PCM up to the loop fade boundary. It then reads the loop tail and head, creates the equal-power crossfade outside the audio callback, and resumes decoding at `A + fadeFrames`. It writes every PCM frame with its source-frame tag into the native ring. The callback copies samples and their preallocated tags, so the playhead can wrap accurately without using a UI timer. A fixed history of rendered frame tags lets the controller estimate the source frame audible after output latency.

### B. Callback-owned loop and crossfade

The native callback would own A/B, loop around at B, and mix the tail with samples from A. The callback does not own a decoder, and making head samples available there would require a second prepared audio buffer, callback-side synchronization, and a separate producer contract. Preloading whole loops would also make memory use grow with loop length.

## Decision

Use candidate A. It keeps decoding, seeking, and crossfade preparation on the existing pump thread, where bounded allocations are allowed. The callback only reads precomputed PCM and frame tags. Ring storage and history remain bounded by configured frame capacity, independent of track length.

For loop edits, serialize the operation in `AndroidPlaybackController`: pause at a native callback boundary, cancel the old pump, reset the ring and decoder to the current valid frame, install the requested region, refill, and resume if playback was active. Publish the new `LoopRegion` only after the native reset and decoder seek succeed. The UI keeps showing the last acknowledged region until then. This favors a clear acknowledgement and no mixed old/new queue state over applying unacknowledged parameters to already queued PCM.

## Proposed data and boundaries

- `LoopRegion`: validated source-frame endpoints, enabled flag, and requested crossfade milliseconds.
- `LoopPlaybackState`: the acknowledged region plus effective crossfade frames after the 25% cap.
- `PlaybackSnapshot`: source frame at the estimated audible output point, play state, and acknowledged `LoopPlaybackState`.
- `PcmRingBuffer::write(samples, frames, sourceStartFrame)`: stores PCM and the linear source-frame tag for each queued frame.
- `NativePlaybackBridge.write(..., sourceStartFrame)`: passes tags into the ring.
- `PlaybackEngine::positionFrame()`: reads a bounded history of rendered source-frame tags at the estimated output-latency offset, so loop wraps do not produce a false monotonic position.
- `AndroidPlaybackController.setLoopRegion(region)`: suspending command; returns only after reset, decoder seek, prefill, and optional playback resume succeed.
- `pumpDecoder()`: emits linear chunks up to the next loop boundary; at the fade boundary, mixes `tail*cos(theta) + head*sin(theta)` with `theta` from 0 to pi/2, writes the mixed tail tagged from `B - fadeFrames`, then continues at `A + fadeFrames`.
- `StudyScreen`: keeps a draft region while the user drags A/B handles; commits on gesture end and replaces the draft with the acknowledged snapshot. Show requested and effective fade values when the cap applies.

## Known trade-off

Changing loop settings while playing pauses and refills the queue before playback resumes. The plan requires an acknowledged safe update but does not require a gapless parameter edit. The actual pause duration must be observed on the target device and kept short; if it is audibly disruptive, revisit the command barrier before extending the controls.

## Bounded cache for short loops

The first on-device run exposed a producer bottleneck for a 10 ms loop. Seeking the MP3 decoder at every cycle caused 704 callback underruns in five seconds. Keep the callback and source-frame-tag design from candidate A, and cache one predecoded cycle only when the loop is at most two seconds. The two-second cap bounds temporary PCM memory independently of track length.

`pumpDecoder()` builds the cycle on its producer coroutine. It reads the loop source once, stores the linear section from `A + fadeFrames` through `B - fadeFrames`, then appends the equal-power blend of the tail and head. Cached writes tag the linear section from `A + fadeFrames` and the blended section from `B - fadeFrames`; the cycle offset wraps within this bounded sample array. Longer loops keep the existing streaming seek path.

On the Redmi, a 10 ms loop near frame 7:39 of a 7:45 MP3 used the 2 ms effective crossfade and played for five seconds without increasing the underrun counter after playback started. A separate 10 ms loop at the start also played for five seconds with the counter at zero. A 12 second loop on a 64 second WAV wrapped from 0:09 to 0:01 while playback remained active. The user listened to repeated transitions and reported no clicks or interruptions.