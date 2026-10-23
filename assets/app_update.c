#include "app_update.h"
#include "update_sha256.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

static AppUpdateState state;
static char status[96]="Press UPDATE to check phone";
static char active_path[256],staging_path[272],backup_path[272],installed_sha[65],offered_sha[65];
static uint32_t offered_size;
static bool hex_digest(const char *s){if(strlen(s)!=64)return false;for(int i=0;i<64;i++)if(!((s[i]>='0'&&s[i]<='9')||(s[i]>='a'&&s[i]<='f')))return false;return true;}
static uint32_t read32(const unsigned char *p){return (uint32_t)p[0]|((uint32_t)p[1]<<8)|((uint32_t)p[2]<<16)|((uint32_t)p[3]<<24);}
static bool inspect(const char *path,uint32_t expected_size){
    struct stat st;if(stat(path,&st)!=0||st.st_size<32||st.st_size>SG_APP_MAX_BYTES||(expected_size&&(uint32_t)st.st_size!=expected_size))return false;
    FILE *f=fopen(path,"rb");if(!f)return false;
    unsigned char h[32];bool ok=fread(h,1,sizeof h,f)==sizeof h&&!memcmp(h,"3DSX",4);
    if(ok){
        unsigned header=h[4]|(h[5]<<8),reloc=h[6]|(h[7]<<8);uint32_t code=read32(h+16),ro=read32(h+20),data=read32(h+24),bss=read32(h+28);
        ok=header>=32&&header<=512&&reloc>=8&&reloc<=512&&code&&bss<=data&&(uint64_t)header+3u*reloc+code+ro+data-bss<=(uint32_t)st.st_size;
    }
    const char marker[]=SG_APP_MARKER;size_t matched=0;bool found=false;unsigned char buffer[4096];size_t n;
    while(ok&&!found&&(n=fread(buffer,1,sizeof buffer,f))){
        for(size_t i=0;i<n;i++){if(buffer[i]==(unsigned char)marker[matched])matched++;else matched=buffer[i]==marker[0]?1:0;if(matched==sizeof marker-1){found=true;break;}}
    }
    ok=ok&&found&&!ferror(f);fclose(f);return ok;
}
bool app_update_validate(const char *path,uint32_t size,const char *expected){
    char digest[65];return hex_digest(expected)&&inspect(path,size)&&sg_sha256_file(path,digest)&&!strcmp(digest,expected);
}
void app_update_init(const char *path,bool homebrew){
    state=APP_UPDATE_IDLE;offered_size=0;offered_sha[0]=installed_sha[0]=0;
    if(!homebrew){state=APP_UPDATE_UNSUPPORTED;snprintf(status,sizeof status,"HOME/CIA: update with FBI; HBL OTA only");return;}
    const char *value=path&&path[0]?path:"/3ds/studygadget/studygadget.3dsx";
    size_t n=strlen(value);
    if(n<5||n>=sizeof active_path||strcmp(value+n-5,".3dsx")||strstr(value,"..")){
        state=APP_UPDATE_UNSUPPORTED;snprintf(status,sizeof status,"Cannot locate running 3dsx");return;
    }
    snprintf(active_path,sizeof active_path,"%s",value);
    snprintf(staging_path,sizeof staging_path,"%s.next",active_path);
    snprintf(backup_path,sizeof backup_path,"%.*s.backup.3dsx",(int)(n-5),active_path);
    // Recover an interrupted rename before any new download. The backup is HBL-launchable.
    struct stat st;
    if(stat(active_path,&st)!=0&&stat(backup_path,&st)==0)rename(backup_path,active_path);
    remove(staging_path);
    sg_sha256_file(active_path,installed_sha);
    snprintf(status,sizeof status,"Press UPDATE; phone must have update file");
}
AppUpdateState app_update_state(void){return state;}
const char *app_update_status(void){return status;}
void app_update_checking(void){state=APP_UPDATE_CHECKING;snprintf(status,sizeof status,"Checking phone for application update");}
void app_update_failed(const char *reason){state=APP_UPDATE_ERROR;snprintf(status,sizeof status,"%s",reason);}
bool app_update_offer(const char *manifest){
    unsigned version;unsigned long size;char digest[65],extra;
    if(sscanf(manifest,"SGAPP %u %lu %64s %c",&version,&size,digest,&extra)!=3||version!=1||size<32||size>SG_APP_MAX_BYTES||!hex_digest(digest)){
        app_update_failed("Invalid application update manifest");return false;
    }
    if(!strcmp(digest,installed_sha)){state=APP_UPDATE_CURRENT;snprintf(status,sizeof status,"Application is up to date");return false;}
    offered_size=(uint32_t)size;snprintf(offered_sha,sizeof offered_sha,"%s",digest);
    state=APP_UPDATE_AVAILABLE;snprintf(status,sizeof status,"Update %lu KB available; press INSTALL",size/1024);return true;
}
const char *app_update_sha(void){return offered_sha;}
uint32_t app_update_size(void){return offered_size;}
const char *app_update_staging(void){return staging_path;}
void app_update_downloading(void){state=APP_UPDATE_DOWNLOADING;snprintf(status,sizeof status,"Receiving application; current file kept");}
bool app_update_install(void){
    if(state!=APP_UPDATE_DOWNLOADING||!app_update_validate(staging_path,offered_size,offered_sha)){
        app_update_failed("Update validation failed; current file kept");remove(staging_path);return false;
    }
    struct stat st;bool had_current=stat(active_path,&st)==0;
    if(had_current){
        if(remove(backup_path)!=0&&stat(backup_path,&st)==0){app_update_failed("Cannot replace backup; current file kept");return false;}
        if(rename(active_path,backup_path)!=0){app_update_failed("Cannot create backup; current file kept");return false;}
    }
    if(rename(staging_path,active_path)!=0){
        if(had_current)rename(backup_path,active_path);
        app_update_failed("Install rename failed; backup kept");return false;
    }
    // Re-read the published SD file before reporting success; restore on corruption.
    if(!app_update_validate(active_path,offered_size,offered_sha)){
        remove(active_path);if(had_current)rename(backup_path,active_path);
        app_update_failed("SD verification failed; backup restored");return false;
    }
    state=APP_UPDATE_INSTALLED;snprintf(status,sizeof status,"Installed. EXIT, then launch again in HBL");return true;
}
