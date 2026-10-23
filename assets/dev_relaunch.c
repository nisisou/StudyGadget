#include "dev_relaunch.h"
#include <3ds.h>
#include <string.h>
#include <stdio.h>

// Rosalina protocol: devkitPro/3ds-hbmenu, source/loaders/rosalina.c.
// Prepare the next 3DSX, then let the caller clean up and exit normally.
bool dev_relaunch_prepare(const char *running_path){
    if(!envIsHomebrew()||!running_path)return false;
    const char *target=running_path;
    if(!strncmp(target,"sdmc:",5))target+=5;
    if(target[0]!='/'||strlen(target)>240||strstr(target,".."))return false;
    Handle loader;Result rc=svcConnectToPort(&loader,"hb:ldr");
    if(R_FAILED(rc))return false;
    u32 *cmdbuf=getThreadCommandBuffer();
    cmdbuf[0]=IPC_MakeHeader(2,0,2);
    cmdbuf[1]=IPC_Desc_StaticBuffer(strlen(target)+1,0);cmdbuf[2]=(u32)target;
    rc=svcSendSyncRequest(loader);if(R_SUCCEEDED(rc))rc=cmdbuf[1];
    if(R_SUCCEEDED(rc)){
        union {u32 align;unsigned char bytes[0x400];} args={0};
        *(u32*)args.bytes=1;snprintf((char*)args.bytes+4,sizeof args.bytes-4,"sdmc:%s",target);
        cmdbuf[0]=IPC_MakeHeader(3,0,2);cmdbuf[1]=IPC_Desc_StaticBuffer(sizeof args.bytes,1);cmdbuf[2]=(u32)args.bytes;
        rc=svcSendSyncRequest(loader);if(R_SUCCEEDED(rc))rc=cmdbuf[1];
    }
    svcCloseHandle(loader);
    if(R_FAILED(rc))return false;
    aptSetChainloaderToSelf();return true;
}

