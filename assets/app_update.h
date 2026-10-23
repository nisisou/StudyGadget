#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#define SG_APP_MARKER "STUDYGADGET_APP_V1"
#define SG_APP_MAX_BYTES (16u*1024u*1024u)
typedef enum { APP_UPDATE_IDLE,APP_UPDATE_CHECKING,APP_UPDATE_AVAILABLE,APP_UPDATE_DOWNLOADING,APP_UPDATE_INSTALLED,APP_UPDATE_CURRENT,APP_UPDATE_ERROR,APP_UPDATE_UNSUPPORTED } AppUpdateState;
void app_update_init(const char *running_path,bool homebrew);
AppUpdateState app_update_state(void);
const char *app_update_status(void);
void app_update_checking(void);
bool app_update_offer(const char *manifest);
void app_update_failed(const char *reason);
const char *app_update_sha(void);
uint32_t app_update_size(void);
const char *app_update_staging(void);
void app_update_downloading(void);
bool app_update_install(void);
bool app_update_validate(const char *path,uint32_t expected_size,const char *expected_sha);
