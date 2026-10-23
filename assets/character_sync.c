#include "character_sync.h"
#include "character.h"
#include "network_peer.h"
#include <3ds.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <poll.h>
#include <errno.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>

#define BASE "/3ds/studygadget/"
static Thread worker;static LightLock lock;static bool lock_ready,done,got_model,got_motions;
static bool cancel_requested,install_announced;
static CharacterSyncProgress progress;
static void set_stage(CharacterSyncStage stage){LightLock_Lock(&lock);progress.stage=stage;progress.received=progress.total=0;LightLock_Unlock(&lock);}
static void set_bytes(size_t received,size_t total){LightLock_Lock(&lock);progress.received=received;progress.total=total;LightLock_Unlock(&lock);}
CharacterSyncProgress character_sync_progress(void){
    if(!lock_ready)return (CharacterSyncProgress){0};
    LightLock_Lock(&lock);CharacterSyncProgress p=progress;LightLock_Unlock(&lock);return p;
}
const char *character_sync_status(void){
    switch(character_sync_progress().stage){
        case CHAR_SYNC_MANIFEST:return "checking phone for updates";
        case CHAR_SYNC_MODEL:return "downloading model";
        case CHAR_SYNC_MOTIONS:return "downloading motions";
        case CHAR_SYNC_APPLYING:return "validating and applying update";
        case CHAR_SYNC_DONE:return "up to date";
        case CHAR_SYNC_ERROR:return "update failed; files kept; retry SYNC";
        default:return "local model; update not started";
    }
}
static bool cancelled(void){LightLock_Lock(&lock);bool c=cancel_requested;LightLock_Unlock(&lock);return c;}
static char host[64],auth[128];static int server_port;static unsigned old_crc,old_revision;
// Bounded Content-Length HTTP/1.1 transfer. Ktor respondBytes provides fixed length;
// chunked/redirect/error responses never reach the SD active file.
static int get(const char *path,FILE *file,char *text,size_t cap,size_t limit){
    set_bytes(0,0);
    char peer[INET_ADDRSTRLEN];if(!network_peer_resolve(host,peer,sizeof peer))return -1;
    int s=socket(AF_INET,SOCK_STREAM,0);if(s<0)return -1;
    struct sockaddr_in a={0};a.sin_family=AF_INET;a.sin_port=htons(server_port);
    if(!inet_aton(peer,&a.sin_addr)){close(s);return -1;}
    int flags=fcntl(s,F_GETFL,0);
    if(flags<0||fcntl(s,F_SETFL,flags|O_NONBLOCK)<0||
       !network_peer_connect(s,(struct sockaddr*)&a,sizeof a,osGetTime()+3000,cancelled)){
        close(s);return -1;
    }
    struct pollfd p={s,POLLIN,0};
    char request[512];int len=snprintf(request,sizeof request,"GET %s HTTP/1.1\r\nHost: %s:%d\r\nAuthorization: Bearer %s\r\nConnection: close\r\n\r\n",path,peer,server_port,auth);
    int sent=0;u64 deadline=osGetTime()+45000;
    while(sent<len&&osGetTime()<deadline&&!cancelled()){p.events=POLLOUT;if(poll(&p,1,1000)<=0)continue;int n=send(s,request+sent,len-sent,0);if(n<0&&(errno==EAGAIN||errno==EWOULDBLOCK||errno==EINTR))continue;if(n<=0){close(s);return -1;}sent+=n;}
    if(sent!=len){close(s);return -1;}
    char header[2048];size_t used=0,written=0,content=0;bool parsed=false;int result=-1;
    while(osGetTime()<deadline&&!cancelled()){
        p.events=POLLIN;if(poll(&p,1,1000)<=0)continue;
        unsigned char buf[4096];int n=recv(s,buf,sizeof buf,0);if(n<0&&(errno==EAGAIN||errno==EWOULDBLOCK||errno==EINTR))continue;if(n<=0)break;
        for(int i=0;i<n;i++){
            if(!parsed){
                if(used>=sizeof header-1)goto end;
                header[used++]=buf[i];header[used]=0;
                if(used>=4&&!memcmp(header+used-4,"\r\n\r\n",4)){
                    int code=0;sscanf(header,"HTTP/1.%*d %d",&code);LightLock_Lock(&lock);progress.http_code=code;LightLock_Unlock(&lock);if(code!=200)goto end;
                    char *line=header;bool found=false;
                    while((line=strstr(line,"\r\n"))){line+=2;if(!strncasecmp(line,"Content-Length:",15)){char *tail;content=strtoul(line+15,&tail,10);found=true;}}
                    if(!found||!content||content>limit||(text&&content>=cap))goto end;
                    parsed=true;set_bytes(0,content);
                }
            }else{
                size_t count=(size_t)(n-i);if(count>content-written)goto end;
                if(file&&fwrite(buf+i,1,count,file)!=count)goto end;
                if(text)memcpy(text+written,buf+i,count);
                written+=count;set_bytes(written,content);break;
            }
        }
        if(parsed&&written==content){if(text)text[written]=0;result=(int)written;break;}
    }
end:close(s);return result;
}
static bool download(const char *url,const char *dest,size_t limit){FILE *f=fopen(dest,"wb");if(!f)return false;bool ok=get(url,f,NULL,0,limit)>0;if(fclose(f)!=0)ok=false;if(!ok)remove(dest);return ok;}
static void run(void *unused){
    (void)unused;char text[160],url[128];unsigned ver,crc,skel,size,rev;bool gm=false,go=false,ok=false;
    set_stage(CHAR_SYNC_MANIFEST);
    if(get("/api/character/manifest",NULL,text,sizeof text,150)>0&&sscanf(text,"SGUP %u %u %u %u %u",&ver,&crc,&skel,&size,&rev)==5&&ver==1&&size<=8*1024*1024){
        ok=true;
        if(crc!=old_crc){set_stage(CHAR_SYNC_MODEL);snprintf(url,sizeof url,"/api/character/model/%u",crc);gm=download(url,BASE "model.download",8*1024*1024);ok=gm;}
        if(ok&&(rev!=old_revision||gm)){set_stage(CHAR_SYNC_MOTIONS);snprintf(url,sizeof url,"/api/character/motions/%u",rev);go=download(url,BASE "motions.download",20000);ok=go;}
        if(gm&&!go){remove(BASE "model.download");gm=false;ok=false;}
    }
    LightLock_Lock(&lock);got_model=gm;got_motions=go;progress.stage=ok?CHAR_SYNC_DONE:CHAR_SYNC_ERROR;done=true;LightLock_Unlock(&lock);
}
void character_sync_start(const char *ip,int port,const char *token){
    if(worker||port<1||port>65535)return;
    if(!lock_ready){LightLock_Init(&lock);lock_ready=true;}
    snprintf(host,sizeof host,"%s",ip);snprintf(auth,sizeof auth,"%s",token);server_port=port;
    old_crc=character_crc();old_revision=character_revision();done=false;got_model=got_motions=false;cancel_requested=false;install_announced=false;
    set_stage(CHAR_SYNC_MANIFEST);worker=threadCreate(run,NULL,32768,0x30,-2,false);if(!worker)set_stage(CHAR_SYNC_ERROR);
}
bool character_sync_poll(void){
    if(!worker)return false;
    LightLock_Lock(&lock);bool finished=done;LightLock_Unlock(&lock);if(!finished)return false;
    if((got_model||got_motions)&&!install_announced){install_announced=true;set_stage(CHAR_SYNC_APPLYING);return false;}
    if(threadJoin(worker,0)!=0)return false;
    threadFree(worker);worker=NULL;
    bool ok=false;bool installing=got_model||got_motions;if(installing)set_stage(CHAR_SYNC_APPLYING);
    if(got_model){
        // Validate model and matching motions before publishing. Restore the previous
        // in-memory model if motion validation fails; on disk old files remain intact.
        if(character_load(BASE "model.download")&&character_motions(BASE "motions.download")){
            remove(BASE "model.previous");remove(BASE "motions.previous");
            bool saved_model=rename(BASE "model.sgch",BASE "model.previous")==0;
            bool saved_motion=rename(BASE "motions.sgmo",BASE "motions.previous")==0;
            bool installed_model=rename(BASE "model.download",BASE "model.sgch")==0;
            bool installed_motion=installed_model&&rename(BASE "motions.download",BASE "motions.sgmo")==0;
            ok=installed_model&&installed_motion;
            if(!ok){
                if(installed_model)remove(BASE "model.sgch");
                if(installed_motion)remove(BASE "motions.sgmo");
                if(saved_model)rename(BASE "model.previous",BASE "model.sgch");
                if(saved_motion)rename(BASE "motions.previous",BASE "motions.sgmo");
            }
        }
        if(!ok){character_load(BASE "model.sgch");character_motions(BASE "motions.sgmo");}
    }else if(got_motions&&character_motions(BASE "motions.download")){
        remove(BASE "motions.previous");bool saved=rename(BASE "motions.sgmo",BASE "motions.previous")==0;
        ok=rename(BASE "motions.download",BASE "motions.sgmo")==0;
        if(!ok){if(saved)rename(BASE "motions.previous",BASE "motions.sgmo");character_motions(BASE "motions.sgmo");}
    }
    if(installing)set_stage(ok?CHAR_SYNC_DONE:CHAR_SYNC_ERROR);
    remove(BASE "model.download");remove(BASE "motions.download");return ok;
}
void character_sync_stop(void){if(worker){LightLock_Lock(&lock);cancel_requested=true;LightLock_Unlock(&lock);threadJoin(worker,U64_MAX);threadFree(worker);worker=NULL;}}
