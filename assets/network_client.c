#include "network_client.h"
#include "network_peer.h"
#include "update_sha256.h"
#include <3ds.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <malloc.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/socket.h>
#include <unistd.h>

#ifndef REQUEST_TIMEOUT_MS
#define REQUEST_TIMEOUT_MS 8000
#endif
static Thread worker;
static LightLock lock;
static bool lock_ready, done, cancel_requested, initialized;
static NetworkStage stage;
static NetworkReply result;
static u32 *soc_buffer;
static struct { char host[64],token[128],method[8],path[96],body[384],destination[272],sha[65];int port;uint32_t bytes; } job;
static uint32_t received_bytes,total_bytes;

static void set_stage(NetworkStage value) {
    LightLock_Lock(&lock); stage=value; LightLock_Unlock(&lock);
}
static bool cancelled(void) {
    LightLock_Lock(&lock); bool value=cancel_requested; LightLock_Unlock(&lock); return value;
}
static bool retryable(void) { return errno==EAGAIN || errno==EWOULDBLOCK || errno==EINTR; }
static int wait_socket(int s, short events, u64 deadline) {
    while (!cancelled()) {
        u64 now=osGetTime(); if (now>=deadline) break;
        u64 left=deadline-now;
        struct pollfd p={s,events,0};
        int n=poll(&p,1,left>100?100:(int)left);
        if (n<0 && errno==EINTR) continue;
        if (n<0) return -1;
        if (n>0) {
            if (p.revents&events) return 1;
            if ((p.revents&POLLHUP) && events==POLLIN) return 1;
            errno=ECONNRESET; return -1;
        }
    }
    errno=cancelled()?ECANCELED:ETIMEDOUT; return -1;
}
static int receive_file(int socket,u64 deadline) {
    char header[2048];size_t used=0,written=0,content=0;bool parsed=false,fixed=false;
    FILE *file=NULL;int answer=-1;SGSha256 digest;sg_sha256_init(&digest);
    while(osGetTime()<deadline && !cancelled()){
        if(wait_socket(socket,POLLIN,deadline)<0)goto end;
        unsigned char buffer[4096];int count=recv(socket,buffer,sizeof buffer,0);
        if(count<0&&retryable())continue;
        if(count<=0){errno=EPROTO;goto end;}
        int offset=0;
        while(!parsed&&offset<count){
            if(used>=sizeof header-1){errno=EMSGSIZE;goto end;}
            header[used++]=buffer[offset++];header[used]=0;
            if(used>=4&&!memcmp(header+used-4,"\r\n\r\n",4)){
                int code=0;if(sscanf(header,"HTTP/1.%*d %d",&code)!=1){errno=EPROTO;goto end;}
                if(code!=200){answer=code;goto end;}
                char *line=strstr(header,"\r\n");
                while(line&&line<header+used-4){
                    line+=2;
                    if(!strncasecmp(line,"Transfer-Encoding:",18)){errno=EPROTO;goto end;}
                    if(!strncasecmp(line,"Content-Length:",15)){
                        char *value=line+15,*tail;while(*value==' '||*value=='\t')value++;
                        unsigned long length=strtoul(value,&tail,10);while(*tail==' '||*tail=='\t')tail++;
                        if(fixed||value==tail||*value<'0'||*value>'9'||strncmp(tail,"\r\n",2)||length!=job.bytes){errno=EMSGSIZE;goto end;}
                        fixed=true;content=length;
                    }
                    line=strstr(line,"\r\n");
                }
                if(!fixed||!content){errno=EPROTO;goto end;}
                file=fopen(job.destination,"wb");if(!file)goto end;
                parsed=true;LightLock_Lock(&lock);total_bytes=content;LightLock_Unlock(&lock);
            }
        }
        if(parsed&&offset<count){
            size_t bytes=(size_t)(count-offset);
            if(bytes>content-written){errno=EMSGSIZE;goto end;}
            if(fwrite(buffer+offset,1,bytes,file)!=bytes)goto end;
            sg_sha256_update(&digest,buffer+offset,bytes);written+=bytes;
            LightLock_Lock(&lock);received_bytes=written;LightLock_Unlock(&lock);
        }
        if(parsed&&written==content){
            char hex[65];sg_sha256_final(&digest,hex);
            if(strcmp(hex,job.sha)){errno=EPROTO;goto end;}
            if(fflush(file)!=0)goto end;
            if(fclose(file)!=0){file=NULL;goto end;}file=NULL;
            answer=200;goto end;
        }
    }
    errno=cancelled()?ECANCELED:ETIMEDOUT;
end: {
    int saved=errno;if(file)fclose(file);
    if(answer!=200)remove(job.destination);
    errno=saved;return answer;
}
}
static int exchange(void) {
    int s=-1, code=-1;
    u64 deadline=osGetTime()+(job.destination[0]?120000:REQUEST_TIMEOUT_MS);
    set_stage(NET_RESOLVING);
    if (!network_peer_resolve(job.host,result.peer,sizeof result.peer)) { errno=ENETUNREACH; return -1; }
    s=socket(AF_INET,SOCK_STREAM,0); if (s<0) return -1;
    int flags=fcntl(s,F_GETFL,0);
    if (flags<0 || fcntl(s,F_SETFL,flags|O_NONBLOCK)<0) goto end;
    struct sockaddr_in address={0}; address.sin_family=AF_INET; address.sin_port=htons(job.port);
    if (inet_pton(AF_INET,result.peer,&address.sin_addr)!=1) { errno=EINVAL; goto end; }
    set_stage(NET_CONNECTING);
    if (connect(s,(struct sockaddr *)&address,sizeof address)<0 && errno!=EINPROGRESS && errno!=EWOULDBLOCK) goto end;
    if (wait_socket(s,POLLOUT,deadline)<0) goto end;
    int error=0; socklen_t length=sizeof error;
    if (getsockopt(s,SOL_SOCKET,SO_ERROR,&error,&length)<0) goto end;
    if (error) { errno=error; goto end; }
    char request[1024];
    int size=snprintf(request,sizeof request,
        "%s %s HTTP/1.1\r\nHost: %s:%d\r\nAuthorization: Bearer %s\r\nContent-Type: application/json\r\nContent-Length: %u\r\nConnection: close\r\n\r\n%s",
        job.method,job.path,result.peer,job.port,job.token,(unsigned)strlen(job.body),job.body);
    if (size<0 || size>=(int)sizeof request) { errno=EMSGSIZE; goto end; }
    set_stage(NET_SENDING);
    int sent=0;
    while (sent<size) {
        if (wait_socket(s,POLLOUT,deadline)<0) goto end;
        int n=send(s,request+sent,size-sent,0);
        if (n<0 && retryable()) continue;
        if (n<=0) goto end;
        sent+=n;
    }
    set_stage(NET_RECEIVING);
    if(job.destination[0]){int answer=receive_file(s,deadline);int saved=errno;close(s);errno=saved;return answer;}
    char wire[4096]; size_t used=0, offset=0, content=0; bool parsed=false, fixed=false;
    for (;;) {
        if (wait_socket(s,POLLIN,deadline)<0) goto end;
        if (used==sizeof wire-1) { errno=EMSGSIZE; goto end; }
        int n=recv(s,wire+used,sizeof wire-1-used,0);
        if (n<0 && retryable()) continue;
        if (n<0) goto end;
        if (n==0) {
            if (!parsed || (fixed && used-offset!=content)) { errno=EPROTO; goto end; }
            break;
        }
        used+=n; wire[used]=0;
        if (!parsed) {
            char *separator=strstr(wire,"\r\n\r\n");
            if (!separator) { if (used>2048) { errno=EMSGSIZE; goto end; } continue; }
            offset=separator+4-wire;
            if (sscanf(wire,"HTTP/1.%*d %d",&code)!=1 || code<100 || code>599) { errno=EPROTO; goto end; }
            char *line=strstr(wire,"\r\n");
            while (line && line<separator) {
                line+=2;
                if (!strncasecmp(line,"Transfer-Encoding:",18)) { errno=EPROTO; goto end; }
                if (!strncasecmp(line,"Content-Length:",15)) {
                    char *value=line+15, *tail;
                    while (*value==' ' || *value=='\t') value++;
                    unsigned long count=strtoul(value,&tail,10);
                    while (*tail==' ' || *tail=='\t') tail++;
                    if (fixed || value==tail || *value<'0' || *value>'9' || strncmp(tail,"\r\n",2) || count>=sizeof result.body) { errno=EMSGSIZE; goto end; }
                    content=count; fixed=true;
                }
                line=strstr(line,"\r\n");
            }
            parsed=true;
        }
        if (used-offset>=sizeof result.body || (fixed && used-offset>content)) { errno=EMSGSIZE; goto end; }
        if (fixed && used-offset==content) break;
    }
    if (used-offset>=sizeof result.body) { errno=EMSGSIZE; goto end; }
    memcpy(result.body,wire+offset,used-offset); result.body[used-offset]=0;
    close(s); return code;
end: { int saved=errno; if (s>=0) close(s); errno=saved; return -1; }
}
static void run(void *unused) {
    (void)unused;
    if (result.initialization) {
        soc_buffer=memalign(0x1000,0x100000);
        result.service_result=soc_buffer?socInit(soc_buffer,0x100000):-1;
        initialized=soc_buffer && R_SUCCEEDED(result.service_result);
        if (!initialized) { free(soc_buffer); soc_buffer=NULL; }
        result.code=initialized?200:-1;
    } else {
        result.code=exchange(); result.error=result.code<0?errno:0;
    }
    LightLock_Lock(&lock);
    stage=result.code==200?(result.initialization?NET_READY:NET_COMPLETE):NET_FAILED;
    done=true;
    LightLock_Unlock(&lock);
}
static bool launch(bool initialization) {
    if (worker) return false;
    if (!lock_ready) { LightLock_Init(&lock); lock_ready=true; }
    memset(&result,0,sizeof result); result.initialization=initialization;
    done=false; cancel_requested=false; received_bytes=total_bytes=0; set_stage(initialization?NET_INITIALIZING:NET_RESOLVING);
    worker=threadCreate(run,NULL,32768,0x30,-2,false);
    if (!worker) { result.code=-1; result.error=ENOMEM; set_stage(NET_FAILED); return false; }
    return true;
}
bool network_client_initialize(void) { return !initialized && launch(true); }
static bool prepare(const char *host,int port,const char *token,const char *method,const char *path,const char *body) {
    if (worker || !initialized || !host || !token || !method || !path || !body || port<1 || port>65535 ||
        strlen(host)>=sizeof job.host || strlen(token)>=sizeof job.token || strlen(method)>=sizeof job.method ||
        strlen(path)>=sizeof job.path || strlen(body)>=sizeof job.body) return false;
    snprintf(job.host,sizeof job.host,"%s",host); snprintf(job.token,sizeof job.token,"%s",token);
    snprintf(job.method,sizeof job.method,"%s",method); snprintf(job.path,sizeof job.path,"%s",path);
    snprintf(job.body,sizeof job.body,"%s",body);job.port=port;job.destination[0]=0;job.sha[0]=0;job.bytes=0;
    return true;
}
bool network_client_start(const char *host,int port,const char *token,const char *method,const char *path,const char *body){
    return prepare(host,port,token,method,path,body)&&launch(false);
}
bool network_client_download_start(const char *host,int port,const char *token,const char *path,const char *destination,uint32_t size,const char *sha){
    if(!destination||!sha||!destination[0]||strlen(destination)>=sizeof job.destination||size<32||size>16u*1024u*1024u||strlen(sha)!=64)return false;
    for(int i=0;i<64;i++)if(!((sha[i]>='0'&&sha[i]<='9')||(sha[i]>='a'&&sha[i]<='f')))return false;
    if(!prepare(host,port,token,"GET",path,""))return false;
    snprintf(job.destination,sizeof job.destination,"%s",destination);snprintf(job.sha,sizeof job.sha,"%s",sha);job.bytes=size;
    return launch(false);
}
NetworkProgress network_client_progress(void){
    if(!lock_ready)return (NetworkProgress){0};
    LightLock_Lock(&lock);NetworkProgress value={stage,received_bytes,total_bytes};LightLock_Unlock(&lock);return value;
}
void network_client_cancel(void){if(lock_ready){LightLock_Lock(&lock);cancel_requested=true;LightLock_Unlock(&lock);}}
bool network_client_poll(NetworkReply *reply) {
    if (!worker) return false;
    LightLock_Lock(&lock); bool ready=done; LightLock_Unlock(&lock);
    if (!ready || threadJoin(worker,0)!=0) return false;
    threadFree(worker); worker=NULL; *reply=result; return true;
}
NetworkStage network_client_stage(void) {
    if (!lock_ready) return NET_IDLE;
    LightLock_Lock(&lock); NetworkStage value=stage; LightLock_Unlock(&lock); return value;
}
void network_client_shutdown(void) {
    if (worker) {
        LightLock_Lock(&lock); cancel_requested=true; LightLock_Unlock(&lock);
        threadJoin(worker,U64_MAX); threadFree(worker); worker=NULL;
    }
    if (initialized) { socExit(); initialized=false; }
    free(soc_buffer); soc_buffer=NULL;
}
