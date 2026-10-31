# Castle of Magic PC – developer notes

## Layout
- `src/org/recompile/castle/` – PC front end (settings screen, window, GPU scaling, input, XInput, frame pacing).
- `tools/Patcher.java` – build-time bytecode patcher (ASM) for the original J2ME jars.
- `patches/*.gates` – per-build list of frame-counted code sites the patcher fixes.
- `tools/ProbePatcher, RmwFinder, TracePatcher, CallTracePatcher, SiteDump, FieldWrites` – analysis tools used to find those sites.
- `tools/test/Harness.java` – headless runner: scripted key presses, screenshots, player-state logging.
- `launcher/` – Go source for `CastleOfMagic.exe`.
- `build.sh` – builds `dist/CastleOfMagic-PC.zip` from the original jars.

## How the high frame rate works
Gameloft hard-coded one phone frame into the engine: 62 ms (240x320 build) or 83 ms (352x416 build)
for animation timers, and 66 ms (16/256 s) for the physics step. The patcher routes both through
`Pacer`, which in smooth mode uses a step of 1-3/256 s and paces frames to match, so real-time speed is unchanged.

Code that changes values by a fixed amount *per frame* is listed in the gate files and patched to
`scale` (proportional, for static fields) or `gate` (apply once per original frame, for per-entity fields).
In the platformer, velocity updates go through one helper; it is gated to original-frame steps with the
original timestep while positions still integrate every frame. This keeps jump arcs identical to the phone's
coarse integration.

## Verification (HD edition, harness logs vs original 12 FPS)
| step | FPS | full jump rise | short hop | running jump |
|------|-----|----------------|-----------|--------------|
| original | 12 | 30539 | 24114 | 26578 |
| 1/256 s | 193 | 30264 | 24037 | 26609 |
| 2/256 s | 96 | 30120 | 24096 | 26666 |
| 3/256 s | 64 | 30802 | 24701 | 27143 |
Walk acceleration and top speed match. Dialog typewriter and story scroll speed match.

## Not done
The 240x320 "Full Adventure" build has more frame-counted movement logic (walk start, ground checks,
air states). Smooth mode for it is disabled; it runs at the original 16 FPS.
