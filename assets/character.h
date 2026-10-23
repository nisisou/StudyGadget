#pragma once
#include <stdbool.h>
#include <stdint.h>
typedef enum { CHAR_IDLE, CHAR_FOCUS, CHAR_BREAK, CHAR_SUCCESS, CHAR_ENCOURAGE, CHAR_SLEEP } CharacterState;
bool character_init(const char *path);
void character_fini(void);
// Call load/init/fini only after a successful FrameBegin and before any draws.
bool character_load(const char *path);
bool character_motions(const char *path);
void character_draw(CharacterState state, float seconds);
uint32_t character_crc(void);
uint32_t character_skeleton(void);
uint32_t character_revision(void);
const char *character_status(void);
