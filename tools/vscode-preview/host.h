#pragma once
#ifndef _CRT_SECURE_NO_WARNINGS
#define _CRT_SECURE_NO_WARNINGS
#endif
#define _USE_MATH_DEFINES
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdbool.h>
#include <string.h>
#include <stdarg.h>
#include <math.h>
#include <time.h>
#include "../../assets/character.h"
#include "../../assets/character_sync.h"
#include "../../assets/network_client.h"
typedef uint32_t u32;
typedef uint64_t u64;
typedef void* C2D_TextBuf;
typedef struct { char value[512]; } C2D_Text;
#define INET_ADDRSTRLEN 16
#define C2D_WithColor 1
#define C2D_AlignCenter 2
#define C2D_AlignRight 4
static u64 preview_now=100000;
static int screen_id=0;
static char frame_json[1024*512];
static size_t json_used;
static void out(const char *fmt,...) {
    if(json_used>=sizeof(frame_json)-1)return;
    va_list v;va_start(v,fmt);
    int n=vsnprintf(frame_json+json_used,sizeof(frame_json)-json_used,fmt,v);va_end(v);
    if(n>0)json_used+=(size_t)n<sizeof(frame_json)-json_used?(size_t)n:sizeof(frame_json)-json_used-1;
}
static void quoted(const char *s) {
    out("\"");for(const unsigned char *p=(const unsigned char*)s;*p;p++){
        if(*p=='"'||*p=='\\')out("\\%c",*p);
        else if(*p<32)out("\\u%04x",*p);else out("%c",*p);
    }out("\"");
}
static u64 osGetTime(void){return preview_now;}
static struct tm *localtime_r(const time_t *t,struct tm *result){localtime_s(result,t);return result;}
static void aptSetSleepAllowed(bool allowed){(void)allowed;}
static u32 C2D_Color32(unsigned r,unsigned g,unsigned b,unsigned a){return r|(g<<8)|(b<<16)|(a<<24);}
static void C2D_TextParse(C2D_Text *t,C2D_TextBuf b,const char *s){(void)b;snprintf(t->value,sizeof t->value,"%s",s);}
static void C2D_TextOptimize(C2D_Text *t){(void)t;}
static void C2D_DrawText(C2D_Text *t,u32 flags,float x,float y,float z,float sx,float sy,u32 c){
    (void)sy;out("[\"text\",%d,%.3f,%.3f,%.3f,%.3f,%u,%u,",screen_id,z,x,y,sx,c,flags);quoted(t->value);out("],");
}
static void C2D_DrawRectangle(float x,float y,float z,float w,float h,u32 a,u32 b,u32 c,u32 d){
    (void)b;(void)d;out("[\"rect\",%d,%.3f,%.3f,%.3f,%.3f,%.3f,%u,%u],",screen_id,z,x,y,w,h,a,c);
}
static void C2D_DrawRectSolid(float x,float y,float z,float w,float h,u32 c){C2D_DrawRectangle(x,y,z,w,h,c,c,c,c);}
static void C2D_DrawCircleSolid(float x,float y,float z,float r,u32 c){out("[\"circle\",%d,%.3f,%.3f,%.3f,%.3f,%u],",screen_id,z,x,y,r,c);}
static void C2D_DrawTriangle(float x,float y,u32 a,float x1,float y1,u32 b,float x2,float y2,u32 c,float z){
    (void)b;(void)c;out("[\"triangle\",%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%u],",screen_id,z,x,y,x1,y1,x2,y2,a);
}
void character_draw(CharacterState state,float seconds){out("[\"character\",%d,0.4,%d,%.3f],",screen_id,state,seconds);}
uint32_t character_crc(void){return 1;}
CharacterSyncProgress character_sync_progress(void){return (CharacterSyncProgress){0};}
const char *character_sync_status(void){return "preview: network simulated";}
NetworkStage network_client_stage(void){return NET_READY;}


#include "../../assets/app_update.h"
AppUpdateState app_update_state(void){return APP_UPDATE_IDLE;}
const char *app_update_status(void){return "preview: application update simulated";}
NetworkProgress network_client_progress(void){return (NetworkProgress){0};}

void app_update_failed(const char *reason){(void)reason;}
