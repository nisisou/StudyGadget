#include <3ds.h>
#include "network_client.h"
#include <string.h>
int host_request_valid(void);
int main(int argc,char **argv){
    if(argc<3)return 2;
    NetworkReply reply;unsigned ticks=0;u64 max_poll=0;
    if(!network_client_initialize())return 3;
    while(!network_client_poll(&reply)){Sleep(1);if(++ticks>5000)return 4;}
    if(reply.code!=200){printf("{\"init_code\":%d,\"service_result\":%d}\n",reply.code,reply.service_result);network_client_shutdown();return 0;}
    u64 start=osGetTime();
    bool started;
    if(!strcmp(argv[2],"download"))started=network_client_download_start("127.0.0.1",8765,"test-pairing-token","/api/update/3ds/test",getenv("TEST_DESTINATION"),(uint32_t)strtoul(getenv("TEST_SIZE"),NULL,10),getenv("TEST_SHA"));
    else started=network_client_start(argc>3?argv[3]:"127.0.0.1",atoi(argv[1]),"test-pairing-token","POST","/api/sync","{\"id\":123}");
    if(!started)return 5;
    u64 launch_ms=osGetTime()-start;ticks=0;
    for(;;){
        u64 before=osGetTime();bool ready=network_client_poll(&reply);u64 spent=osGetTime()-before;if(spent>max_poll)max_poll=spent;
        if(ready)break;
        ticks++;Sleep(1);
        if(!strcmp(argv[2],"cancel")&&osGetTime()-start>120){u64 stop=osGetTime();network_client_shutdown();printf("{\"cancel_ms\":%llu,\"ticks\":%u}\n",(unsigned long long)(osGetTime()-stop),ticks);return 0;}
        if(osGetTime()-start>12000)return 6;
    }
    printf("{\"code\":%d,\"error\":%d,\"ticks\":%u,\"elapsed_ms\":%llu,\"launch_ms\":%llu,\"max_poll_ms\":%llu,\"body_hex\":\"",reply.code,reply.error,ticks,(unsigned long long)(osGetTime()-start),(unsigned long long)launch_ms,(unsigned long long)max_poll);
    for(unsigned i=0;reply.body[i];i++)printf("%02x",(unsigned char)reply.body[i]);
    printf("\",\"request_valid\":%d}\n",host_request_valid());network_client_shutdown();return 0;
}
