#include "network_peer.h"
#include <3ds.h>
#include <string.h>
#include <arpa/inet.h>

bool network_peer_resolve(const char *configured, char *out, size_t capacity) {
    if (!configured || !out || capacity < INET_ADDRSTRLEN) return false;
    struct in_addr address;
    if (strcmp(configured, "auto") != 0) {
        if (inet_pton(AF_INET, configured, &address) != 1) return false;
        return inet_ntop(AF_INET, &address, out, capacity) != NULL;
    }
    // Do not guess 192.168.43.1 or a /24 .1 address: Android may assign another subnet.
    SOCU_RoutingTableEntry routes[16] = {0};
    socklen_t length = sizeof routes;
    if (SOCU_GetNetworkOpt(SOL_CONFIG, NETOPT_ROUTING_TABLE, routes, &length) != 0) return false;
    size_t count = length / sizeof routes[0];
    if (count > sizeof routes / sizeof routes[0]) count = sizeof routes / sizeof routes[0];
    for (size_t i = 0; i < count; i++) {
        SOCU_RoutingTableEntry *route = &routes[i];
        if (route->dest_ip.s_addr == 0 && route->netmask.s_addr == 0 &&
            (route->flags & ROUTING_FLAG_G) && route->gateway.s_addr != 0) {
            return inet_ntop(AF_INET, &route->gateway, out, capacity) != NULL;
        }
    }
    return false;
}
