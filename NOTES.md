# Castle of Magic PC – developer notes

The original game jars are packaged **unchanged** (build.sh checks they are byte-identical).
The game runs at its own original speed; nothing in the game is patched.

What this project adds around the game:
- `src/org/recompile/castle/` – PC front end: settings screen, window/fullscreen, GPU scaling filters,
  keyboard and XInput controller mapping to the phone keys, screenshots, FPS counter.
- `launcher/` – Go source for `CastleOfMagic.exe`, which starts the bundled Java runtime.
- `build.sh` – builds `dist/CastleOfMagic-PC.zip` from the original jars.
- `tools/MakeIco.java` – makes the Windows icon from the game's icon.
- `tools/test/` – headless test runner (scripted keys, screenshots).

The phone runtime is FreeJ2ME-Plus (GPLv3), downloaded and compiled at build time.

## Multiplayer (Normal / 240x320 build)
Nothing in the game is patched. `Coop.java` reads the running game with reflection
(state `i.aR`, world/level `g.i/g.j/g.k`, hero `f.h` position and animation, camera `f.aa/f.ab`,
castle hero `g.az/g.aA/g.aB`) and draws the friend with the game's own sprite object into a copy
of each frame, using a yellow palette added at runtime. `MultiSession`/`RoomClient` talk to the
room server (create/join, WebSocket relay of ~16 state messages per second, save copy host -> guest).
`tools/test/CoopNetTest.java` runs a host and a guest game headless against a local room server.
