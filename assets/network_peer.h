#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <3ds.h>
#include <sys/socket.h>
// "auto" means the gateway of the connected phone hotspot; IPv4 keeps LAN compatibility.
bool network_peer_resolve(const char *configured, char *out, size_t capacity);
bool network_peer_connect(int socket, const struct sockaddr *address, socklen_t address_length,
                          u64 deadline, bool (*is_cancelled)(void));
