#pragma once
#include <stdbool.h>
#include <stdint.h>

typedef enum {
    NET_IDLE, NET_INITIALIZING, NET_READY, NET_RESOLVING, NET_CONNECTING,
    NET_SENDING, NET_RECEIVING, NET_COMPLETE, NET_FAILED
} NetworkStage;
typedef struct {
    bool initialization;
    int code, error;
    int32_t service_result;
    char peer[16], body[2048];
} NetworkReply;

bool network_client_initialize(void);
bool network_client_start(const char *host, int port, const char *token,
                          const char *method, const char *path, const char *body);
bool network_client_download_start(const char *host,int port,const char *token,const char *path,const char *destination,uint32_t size,const char *sha256);
typedef struct { NetworkStage stage;uint32_t received,total; } NetworkProgress;
NetworkProgress network_client_progress(void);
void network_client_cancel(void);
bool network_client_poll(NetworkReply *reply);
NetworkStage network_client_stage(void);
void network_client_shutdown(void);
