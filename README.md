# Castle of Magic – PC version

A Windows PC package for Castle of Magic (Gameloft, 2008, J2ME). Personal use only.

- Double-click `CastleOfMagic.exe`; a PC settings screen opens first (F1 reopens it in-game).
- HD Edition (352x416) runs at up to 193 FPS with the original game speed, jump heights and timers.
- Full Adventure (240x320) runs at its original 16 FPS.
- GPU scaling with 4 filters, fullscreen/window, VSync, XInput controllers, FPS counter, screenshots.
- Bundled Java runtime, so nothing needs to be installed.

![Settings screen](docs/settings-screen.png)

## Building

The original game jars are **not** in this repo. Put them in a folder and run:

```sh
./build.sh "/path/to/folder with Castle of Magic jars"
```

It expects `Castle of Magic (2008)(Gameloft SA)(v1.0.2)(a8).jar` and `...(a9).jar`.
Needs JDK 25, Go 1.22+, git, curl, unzip and zip. Output: `dist/CastleOfMagic-PC.zip`.

Player instructions: [README.txt](README.txt). How the patching works: [NOTES.md](NOTES.md).

## Credits
- Phone runtime: [FreeJ2ME-Plus](https://github.com/TASEmulators/freej2me-plus) (GPLv3), downloaded at build time.
- Java runtime: Eclipse Temurin 25.
- Castle of Magic is (c) 2008 Gameloft.
