#!/usr/bin/env bash
# Builds the Castle of Magic PC package (Windows x64).
#
#   ./build.sh <folder with the original Castle of Magic jars>
#
# Needs: JDK 25, Go 1.22+, git, curl, unzip, zip. Output: dist/CastleOfMagic-PC.zip
set -euo pipefail

SRC_JARS="${1:?usage: ./build.sh <folder with the original jars>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
B="$HERE/build"
CACHE="$HERE/.cache"
JAVA_HOME="${JAVA_HOME:-/root/.local/share/mise/installs/java/25}"
J="$JAVA_HOME/bin"
FREEJ2ME_COMMIT=3933e75
ASM_VER=9.8
JRE_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jre_x64_windows_hotspot_25.0.5_8.zip"

ADV="$SRC_JARS/Castle of Magic (2008)(Gameloft SA)(v1.0.2)(a8).jar"   # 240x320 full adventure
HD="$SRC_JARS/Castle of Magic (2008)(Gameloft SA)(v1.0.2)(a9).jar"    # 352x416 HD platformer

mkdir -p "$B" "$CACHE"

echo "== FreeJ2ME-Plus ($FREEJ2ME_COMMIT)"
if [ ! -d "$CACHE/freej2me-plus" ]; then
  git clone -q https://github.com/TASEmulators/freej2me-plus.git "$CACHE/freej2me-plus"
fi
git -C "$CACHE/freej2me-plus" checkout -q "$FREEJ2ME_COMMIT" 2>/dev/null || git -C "$CACHE/freej2me-plus" fetch -q --unshallow || true
F="$CACHE/freej2me-plus"

echo "== ASM $ASM_VER (build tools only)"
for a in asm asm-tree; do
  [ -f "$CACHE/$a.jar" ] || curl -sSfL -o "$CACHE/$a.jar" "https://repo1.maven.org/maven2/org/ow2/asm/$a/$ASM_VER/$a-$ASM_VER.jar"
done
TOOLCP="$CACHE/asm.jar:$CACHE/asm-tree.jar"
mkdir -p "$B/tools"
"$J/javac" -cp "$TOOLCP" -d "$B/tools" "$HERE/tools/Patcher.java"

echo "== Patch game jars"
mkdir -p "$B/games"
"$J/java" -cp "$TOOLCP:$B/tools" Patcher "$HD"  "$B/games/platformer.jar" "$HERE/patches/platformer.gates"
"$J/java" -cp "$TOOLCP:$B/tools" Patcher "$ADV" "$B/games/adventure.jar"  "$HERE/patches/adventure.gates"

echo "== Compile runtime + PC front end"
rm -rf "$B/jar" && mkdir -p "$B/jar"
find "$F/src" -name '*.java' -not -path '*/libretro/*' -not -path '*/win32pad/*' > "$B/freej2me-sources.txt"
"$J/javac" --release 8 -nowarn -Xlint:none -encoding utf-8 -d "$B/jar" @"$B/freej2me-sources.txt"
"$J/javac" --release 25 -Xlint:none -cp "$B/jar" -d "$B/jar" "$HERE"/src/org/recompile/castle/*.java
cp -r "$F/resources/." "$B/jar/"
mkdir -p "$B/jar/META-INF" && cp -r "$F/META-INF/." "$B/jar/META-INF/"
printf 'Main-Class: org.recompile.castle.CastleOfMagicPC\n' > "$B/manifest.txt"
rm -f "$B/CastleOfMagicPC.jar"
"$J/jar" cfm "$B/CastleOfMagicPC.jar" "$B/manifest.txt" -C "$B/jar" .

echo "== Windows launcher"
( cd "$HERE/launcher"
  [ -f go.mod ] || go mod init castleofmagic/launcher >/dev/null
  GOOS=linux go run github.com/akavel/rsrc@v0.10.2 -ico icon.ico -arch amd64 -o rsrc_windows_amd64.syso
  GOOS=windows GOARCH=amd64 go build -ldflags "-H windowsgui -s -w" -o "$B/CastleOfMagic.exe" . )

echo "== Java runtime (Windows x64)"
if [ ! -d "$CACHE/jre" ]; then
  curl -sSfL -o "$CACHE/jre.zip" "$JRE_URL"
  mkdir -p "$CACHE/jre" && unzip -q "$CACHE/jre.zip" -d "$CACHE/jre"
fi
JRE_DIR="$(ls -d "$CACHE"/jre/jdk-*)"

echo "== Assemble"
D="$HERE/dist/CastleOfMagic"
rm -rf "$HERE/dist" && mkdir -p "$D/app/games"
cp "$B/CastleOfMagic.exe" "$D/"
cp "$B/CastleOfMagicPC.jar" "$D/app/"
cp "$B/games/platformer.jar" "$B/games/adventure.jar" "$D/app/games/"
cp -r "$JRE_DIR" "$D/runtime"
cp "$HERE/README.txt" "$D/"
mkdir -p "$D/licenses"
cp "$F/LICENSE" "$D/licenses/FreeJ2ME-Plus-GPLv3.txt"
( cd "$HERE/dist" && zip -q -r -9 CastleOfMagic-PC.zip CastleOfMagic )
ls -la "$HERE/dist"
