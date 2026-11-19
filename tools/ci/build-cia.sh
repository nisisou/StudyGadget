#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(pwd)"
TOOLS_DIR="${RUNNER_TEMP:-/tmp}/ctr-tools"
STAGING="${RUNNER_TEMP:-/tmp}/studygadget-cia-build"
HOME_DIR="$ROOT_DIR/home-menu"

test -s "$ROOT_DIR/assets/studygadget.elf"
test -s "$HOME_DIR/banner.cgfx"
test -s "$HOME_DIR/icon.png"
test -s "$HOME_DIR/silence.wav"
test -s "$HOME_DIR/studygadget.rsf"

mkdir -p "$TOOLS_DIR"
BANNERTOOL="${BANNERTOOL_BIN:-$(command -v bannertool || true)}"
MAKEROM="${MAKEROM_BIN:-$(command -v makerom || true)}"

if [ -z "$BANNERTOOL" ]; then
  if [ ! -x "$TOOLS_DIR/bannertool/build/bannertool" ] && [ ! -x "$TOOLS_DIR/bannertool/bannertool" ]; then
    git clone --depth 1 https://github.com/carstene1ns/3ds-bannertool.git "$TOOLS_DIR/bannertool"
    cmake -S "$TOOLS_DIR/bannertool" -B "$TOOLS_DIR/bannertool/build" -DCMAKE_BUILD_TYPE=Release
    cmake --build "$TOOLS_DIR/bannertool/build" -j"$(nproc)"
  fi
  BANNERTOOL="$(find "$TOOLS_DIR/bannertool" -type f -name bannertool -executable | head -n 1)"
fi

if [ -z "$MAKEROM" ]; then
  if [ ! -x "$TOOLS_DIR/Project_CTR/makerom/bin/makerom" ] && [ ! -x "$TOOLS_DIR/Project_CTR/makerom/makerom" ]; then
    git clone --depth 1 https://github.com/3DSGuy/Project_CTR.git "$TOOLS_DIR/Project_CTR"
    make -C "$TOOLS_DIR/Project_CTR/makerom" deps -j"$(nproc)" || true
    make -C "$TOOLS_DIR/Project_CTR/makerom" -j"$(nproc)"
  fi
  MAKEROM="$(find "$TOOLS_DIR/Project_CTR/makerom" -type f -name makerom -executable | head -n 1)"
fi

test -x "$BANNERTOOL"
test -x "$MAKEROM"

rm -rf "$STAGING"
mkdir -p "$STAGING"
cp "$HOME_DIR/banner.cgfx" "$HOME_DIR/icon.png" "$HOME_DIR/silence.wav" "$HOME_DIR/studygadget.rsf" "$ROOT_DIR/assets/studygadget.elf" "$STAGING/"

pushd "$STAGING" >/dev/null
"$BANNERTOOL" makesmdh \
  -s "Study Gadget" \
  -l "Character study timer" \
  -p "Study Gadget" \
  -f "visible,allow3d,recordusage,extendedbanner" \
  -i icon.png \
  -o icon.smdh

"$BANNERTOOL" makebanner \
  -ci banner.cgfx \
  -a silence.wav \
  -o banner.bnr

"$MAKEROM" -f cia -target t -desc app:4 -exefslogo \
  -o studygadget-new.cia \
  -elf studygadget.elf \
  -rsf studygadget.rsf \
  -icon icon.smdh \
  -banner banner.bnr \
  -major 1 -minor 2 -micro 0

cp studygadget-new.cia "$ROOT_DIR/assets/studygadget.cia"
popd >/dev/null

test -s "$ROOT_DIR/assets/studygadget.cia"
echo "Built CIA: $(wc -c < "$ROOT_DIR/assets/studygadget.cia") bytes"
