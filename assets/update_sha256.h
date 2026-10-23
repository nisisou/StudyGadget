#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
typedef struct { uint32_t h[8];uint64_t bytes;uint8_t block[64];size_t used; } SGSha256;
void sg_sha256_init(SGSha256 *s);
void sg_sha256_update(SGSha256 *s,const void *data,size_t size);
void sg_sha256_final(SGSha256 *s,char hex[65]);
bool sg_sha256_file(const char *path,char hex[65]);
