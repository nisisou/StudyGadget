#pragma once
#include <stdbool.h>
#include <stddef.h>
// "auto" means the gateway of the connected phone hotspot; IPv4 keeps LAN compatibility.
bool network_peer_resolve(const char *configured, char *out, size_t capacity);
