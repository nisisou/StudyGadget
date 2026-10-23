ifeq ($(strip $(DEVKITPRO)),)
$(error DEVKITPRO is not set)
endif
PYTHON ?= python3
CC := $(DEVKITPRO)/devkitARM/bin/arm-none-eabi-gcc
TOOL := $(DEVKITPRO)/tools/bin/3dsxtool
CTRU := $(DEVKITPRO)/libctru
ARCH := -march=armv6k -mtune=mpcore -mfloat-abi=hard -mtp=soft
CFLAGS := -std=gnu11 -Wall -O2 -mword-relocations -ffunction-sections $(ARCH) -D__3DS__ -I$(CTRU)/include -I$(DEVKITPRO)/portlibs/3ds/include
LDFLAGS := -specs=3dsx.specs $(ARCH) -L$(CTRU)/lib -L$(DEVKITPRO)/portlibs/3ds/lib
all: studygadget.3dsx
character.shbin: character.v.pica
	$(DEVKITPRO)/tools/bin/picasso -o $@ $<
character_shader.c: character.shbin
	$(PYTHON) ../tools/embed_shader.py $< character_shader.c character_shader.h
character_shader.h: character_shader.c
	@test -f $@ || $(PYTHON) ../tools/embed_shader.py character.shbin character_shader.c character_shader.h
studygadget.elf: main.c character.c character.h character_sync.c character_sync.h network_peer.c network_peer.h network_client.c network_client.h app_update.c app_update.h dev_relaunch.c dev_relaunch.h update_sha256.c update_sha256.h character_shader.c character_shader.h
	$(CC) $(CFLAGS) main.c character.c character_sync.c network_client.c network_peer.c app_update.c update_sha256.c dev_relaunch.c character_shader.c $(LDFLAGS) -lcitro2d -lcitro3d -lctru -lm -o $@
../home-menu/studygadget.smdh: ../character/model.sgch ../tools/export_home_banner.py
	$(PYTHON) ../tools/export_home_banner.py
	$(DEVKITPRO)/tools/bin/smdhtool --create "Study Gadget" "Character study timer" "Study Gadget" ../home-menu/icon.png $@ ../home-menu/icon-small.png
studygadget.3dsx: studygadget.elf ../home-menu/studygadget.smdh
	$(TOOL) $< $@ --smdh=../home-menu/studygadget.smdh
clean:
	rm -f *.elf *.3dsx
