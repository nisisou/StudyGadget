#pragma once
#include <stdbool.h>
#include <stdint.h>
typedef enum { CHAR_SYNC_IDLE, CHAR_SYNC_MANIFEST, CHAR_SYNC_MODEL, CHAR_SYNC_MOTIONS, CHAR_SYNC_APPLYING, CHAR_SYNC_DONE, CHAR_SYNC_ERROR } CharacterSyncStage;
typedef struct { CharacterSyncStage stage; uint32_t received,total; int http_code; } CharacterSyncProgress;
void character_sync_start(const char *ip,int port,const char *token);
bool character_sync_poll(void);
void character_sync_stop(void);
CharacterSyncProgress character_sync_progress(void);
const char *character_sync_status(void);
