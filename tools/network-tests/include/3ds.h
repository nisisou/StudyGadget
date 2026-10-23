#pragma once
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0601
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <errno.h>
#include <stdio.h>
typedef uint32_t u32;
typedef uint64_t u64;
typedef int socklen_t;
#define U64_MAX UINT64_MAX
#define R_SUCCEEDED(x) ((x)>=0)
typedef CRITICAL_SECTION LightLock;
typedef struct HostThread *Thread;
void LightLock_Init(LightLock *lock);
void LightLock_Lock(LightLock *lock);
void LightLock_Unlock(LightLock *lock);
Thread threadCreate(void (*run)(void *),void *arg,size_t stack,int priority,int core,bool detached);
int threadJoin(Thread worker,u64 timeout);
void threadFree(Thread worker);
u64 osGetTime(void);
int socInit(u32 *buffer,size_t size);
void socExit(void);
int host_socket(int family,int kind,int protocol);
int host_connect(int s,const struct sockaddr *address,int size);
int host_send(int s,const void *buffer,int length,int flags);
int host_recv(int s,void *buffer,int length,int flags);
int host_getsockopt(int s,int level,int option,void *value,int *size);
int host_poll(struct pollfd *fds,unsigned long count,int timeout);
int host_close(int s);
#ifndef HOST_IMPLEMENTATION
#define socket host_socket
#define connect host_connect
#define send host_send
#define recv host_recv
#define getsockopt host_getsockopt
#define poll host_poll
#define close host_close
#endif
#define strncasecmp _strnicmp
#define memalign(alignment,size) malloc(size)
