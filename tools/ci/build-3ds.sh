#!/usr/bin/env bash
set -euo pipefail
: "${DEVKITPRO:=/opt/devkitpro}"
export DEVKITPRO
test -x "$DEVKITPRO/devkitARM/bin/arm-none-eabi-gcc"
test -x "$DEVKITPRO/tools/bin/smdhtool"
command -v python3 >/dev/null
"$DEVKITPRO/tools/bin/smdhtool" --create \
  "Study Gadget" "Character study timer" "Study Gadget" \
  home-menu/icon.png home-menu/studygadget.smdh home-menu/icon-small.png
# Fixed generated paths in an ephemeral CI checkout.
rm -f assets/studygadget.elf assets/studygadget.3dsx \
  assets/character.shbin assets/character_shader.c assets/character_shader.h
make -C assets PYTHON=python3
test -s assets/studygadget.3dsx
python3 - <<'PY'
from pathlib import Path
data=Path('assets/studygadget.3dsx').read_bytes()
assert data[:4]==b'3DSX' and b'STUDYGADGET_APP_V1' in data
assert len(data)<=16*1024*1024
print('Built update-capable Study Gadget 3DSX:',len(data),'bytes')
PY

