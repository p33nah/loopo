# Neutral waveform controls: synthesized design

## Problem
The study screen is used while playing. Its waveform, transport controls, and cards currently share a green cast; the supplied launcher artwork is a white mark on opaque black; the loop row gives all remaining width to Fade and changes the Loop label based on state. The design must clarify the controls without moving them or reducing touch targets.

## Usage (caller's view)
The user sees `LOOPO` followed by the same centered white mark used by the launcher. The waveform and general transport/fade controls use neutral gray and off-white. A and B retain their teal and amber identities. The Loop button always reads `Loop`, with a calm neutral outline/fill reflecting its state; accessibility semantics announce active, inactive, or pending. Fade stays beside its value at the far right and has a much shorter track.

Existing calls remain unchanged:

```kotlin
TopBar(track, actionLabel, onOpenTrack)
LoopControls(track, region, positionFrame, effectiveCrossfadeFrames, enabled, onRegionChange, onCommit)
WaveformCanvas(track, waveform, playback, loopRegion, viewport, onViewportChange, ..., onLoopRegionChange, ...)
```

## Shape
No new domain shape, state, callback, or composable API is needed. `LoopRegion(aFrame, bFrame, enabled, crossfadeMs)` remains the only source for loop state and fade value. The visible label remains constant; active/pending feedback derives from `region.enabled` and the existing `enabled` argument (`principle-model-the-domain`).

- `Color.kt` stays the palette owner. Neutralize `Background`, `Card`, `Line`, `Accent`, `Waveform`, `TextPrimary`, and `Muted`; leave `LoopA`, `LoopB`, and `Playhead` unchanged.
- `TopBar()` renders a small centered `Image` immediately after `LOOPO`, using a transparent derivative of `ic_launcher_foreground.png`. The black source canvas becomes alpha proportional to luminance, preserving the exact white artwork and antialiased edges; this prevents a visible square without forcing every screen to pure black.
- `LoopControls()` remains one touch-friendly row. Keep A/B/Loop at the left, insert a weighted spacer, then place the Fade text and a `widthIn(max = 220.dp)` slider at the trailing edge. The current landscape screenshot shows roughly 440 dp of track; 220 dp halves it. Keep the existing 48 dp minimum interaction height.
- Keep `Text("Loop")` in all states. Derive a thin neutral border and subtle neutral fill for active; disabled/pending state uses subdued styling and a screen-reader state description. This preserves state feedback without changing measured button width.
- General slider colors continue to use the shared `Accent`, so transport, speed, and Fade remain consistent. A/B colors remain semantic and untouched.

The UI surface already owns these presentation decisions; avoid new abstractions for a small Compose change. No new public interface is exposed, and the same callbacks continue to carry edits.

## Synthesis decision
Candidate B is the base. It preserves the charcoal screen while avoiding a black tile behind the inline icon, and its two-zone row makes Fade's trailing alignment explicit. Candidate A proposed changing the entire app background to true black and reusing the opaque PNG; it was rejected because it changes every screen surface to accommodate a 24 dp logo. The base is grafted with candidate A's emphasis on preserving the 48 dp touch target and bounding the fade slider. Both candidates kept `LoopRegion` authoritative and the visible label stable.

The rubric was: no visible icon tile and centered artwork; neutral waveform/general controls while preserving A/B colors; no layout shift with clear active/pending and accessible status; Fade about half as wide, trailing-aligned, and touchable; smallest coherent change. Candidate B leads on logo blending, state announcement, and explicit grouping; both meet palette and touch criteria. A cross-judge was skipped because the configured runner and judge pool only contains gpt-6-luna, so no different model family was available.

## Tradeoffs accepted
- We accept one small derived image resource in exchange for exact brand artwork on a charcoal surface.
- We accept a 220 dp maximum track width rather than a screen percentage; the bound makes the control compact while allowing it to shrink on constrained screens.
- We accept a constant visible Loop label and expose its state through visual and accessibility semantics.
- We retain a 48 dp touch area although the slider track is visually shorter.

## Alternatives considered
- Make the whole app background pure black and reuse the opaque icon PNG. This avoids image processing but changes all screens to blend one icon into the background.
- Keep a single growing Fade slider and text-switching Loop label. This uses less layout code but leaves the label shift and the control's visual dominance intact.
- Add a second local palette or state variable for active Loop styling. This duplicates shared theme/state decisions and can drift from the existing `LoopRegion`.

## Implementation reconciliation
No deviation accepted yet. Keep resource conversion deterministic and review the new image's visible alpha bounds and center before shipping. Adjust the maximum width only if the real Xiaomi layout shows the A/B/Loop group or Fade value clipping.

## Open questions and risks
- The 220 dp cap is based on the landscape Xiaomi layout. Inspect the supported narrowest layout so the Fade group does not crowd A/B/Loop.
- The launcher foreground PNG is opaque; alpha extraction must preserve its exact mark and antialiasing.

## Next implementation step
Create and inspect the transparent mark, then update the shared theme tokens and the existing `TopBar`/`LoopControls` presentation without changing state ownership.
