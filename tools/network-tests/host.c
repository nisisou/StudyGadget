// Deterministic transport adapter: no host network access is required.
#define HOST_IMPLEMENTATION
#include <3ds.h>
#include <process.h>
#include <string.h>
#include "network_peer.h"
struct HostThread { HANDLE handle; void (*run)(void *); void *arg; };
void LightLock_Init(LightLock *lock){InitializeCriticalSection(lock);}
void LightLock_Lock(LightLock *lock){EnterCriticalSection(lock);}
void LightLock_Unlock(LightLock *lock){LeaveCriticalSection(lock);}
static unsigned __stdcall entry(void *value){Thread t=value;t->run(t->arg);return 0;}
Thread threadCreate(void (*run)(void *),void *arg,size_t stack,int priority,int core,bool detached){
    (void)stack;(void)priority;(void)core;(void)detached;
    Thread t=calloc(1,sizeof *t);if(!t)return NULL;t->run=run;t->arg=arg;
    t->handle=(HANDLE)_beginthreadex(NULL,0,entry,t,0,NULL);if(!t->handle){free(t);return NULL;}return t;
}
int threadJoin(Thread t,u64 timeout){return WaitForSingleObject(t->handle,timeout==U64_MAX?INFINITE:(DWORD)(timeout/1000000))==WAIT_OBJECT_0?0:1;}
void threadFree(Thread t){CloseHandle(t->handle);free(t);}
u64 osGetTime(void){return GetTickCount64();}
static char response[1024*1024],request[1200];
static size_t response_size,position,header_size,request_size;
static u64 available_at;
static int receive_calls,send_calls;
static bool scenario(const char *name){const char *s=getenv("TEST_SCENARIO");return s&&!strcmp(s,name);}
int socInit(u32 *buffer,size_t size){
    (void)buffer;(void)size;if(getenv("TEST_SOC_FAIL"))return -42;
    const char *path=getenv("TEST_RESPONSE_FILE");
    if(path){FILE *f=fopen(path,"rb");if(!f)return -2;response_size=fread(response,1,sizeof response-1,f);fclose(f);}
    char *end=strstr(response,"\r\n\r\n");header_size=end?(size_t)(end+4-response):0;
    return 0;
}
void socExit(void){}
int host_socket(int family,int type,int protocol){(void)family;(void)type;(void)protocol;return 1;}
int host_connect(int s,const struct sockaddr *address,int size){
    (void)s;(void)address;(void)size;available_at=osGetTime()+(scenario("fragmented")?60:0);errno=EINPROGRESS;return -1;
}
int host_getsockopt(int s,int level,int option,void *value,int *size){(void)s;(void)level;(void)option;(void)size;*(int *)value=0;return 0;}
int host_poll(struct pollfd *fds,unsigned long count,int timeout){
    (void)count;
    if(scenario("refused")){fds[0].revents=POLLERR;return 1;}
    if(fds[0].events==POLLOUT){fds[0].revents=POLLOUT;return 1;}
    if(scenario("silent")){Sleep(timeout);return 0;}
    if(scenario("keepalive")&&position==response_size){Sleep(timeout);return 0;}
    u64 now=osGetTime();
    if(now<available_at){u64 wait=available_at-now;Sleep(wait>(u64)timeout?timeout:(DWORD)wait);return 0;}
    fds[0].revents=POLLIN;return 1;
}
int host_send(int s,const void *buffer,int length,int flags){
    (void)s;(void)flags;send_calls++;
    if(scenario("retryable")&&send_calls==1){errno=EAGAIN;return -1;}
    int n=scenario("partial-send")&&length>7?7:length;
    if(request_size+n<sizeof request){memcpy(request+request_size,buffer,n);request_size+=n;}
    return n;
}
int host_recv(int s,void *buffer,int length,int flags){
    (void)s;(void)flags;receive_calls++;
    if(scenario("retryable")&&receive_calls==1){errno=EAGAIN;return -1;}
    size_t n=response_size-position;if(n>(size_t)length)n=length;
    if(scenario("fragmented")){if(n>5)n=5;available_at=osGetTime()+30;}
    if(scenario("drip")){if(position<header_size)n=header_size-position;else if(n>1)n=1;available_at=osGetTime()+1000;}
    memcpy(buffer,response+position,n);position+=n;return (int)n;
}
int host_close(int s){(void)s;return 0;}
int fcntl(int s,int command,int flags){(void)s;(void)command;(void)flags;return 0;}
bool network_peer_resolve(const char *configured,char *out,size_t capacity){
    unsigned a,b,c,d;char extra;
    if(sscanf(configured,"%u.%u.%u.%u%c",&a,&b,&c,&d,&extra)!=4||a>255||b>255||c>255||d>255)return false;
    return snprintf(out,capacity,"%u.%u.%u.%u",a,b,c,d)>0;
}
int host_request_valid(void){return strstr(request,"Authorization: Bearer test-pairing-token\r\n")&&strstr(request,"Content-Length: 10\r\n")&&strstr(request,"\r\n\r\n{\"id\":123}")?1:0;}
