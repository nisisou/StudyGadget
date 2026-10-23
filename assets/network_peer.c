#include "network_peer.h"
#include <3ds.h>
#include <string.h>
#include <arpa/inet.h>
#include <errno.h>
#include <poll.h>
#include <sys/socket.h>

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

static int network_socket_error(int error) {
    if (error >= 0) return error;
    switch (-error) {
        case 13: return ECONNABORTED;
        case 14: return ECONNREFUSED;
        case 15: return ECONNRESET;
        case 23: return EHOSTUNREACH;
        case 26: return EINPROGRESS;
        case 38: return ENETDOWN;
        case 39: return ENETRESET;
        case 40: return ENETUNREACH;
        case 56: return ENOTCONN;
        case 76: return ETIMEDOUT;
        default: return EIO;
    }
}

bool network_peer_connect(int socket, const struct sockaddr *address, socklen_t address_length,
                          u64 deadline, bool (*is_cancelled)(void)) {
    if (connect(socket, address, address_length) == 0) return true;
    if (errno != EINPROGRESS && errno != EWOULDBLOCK) return false;

    while (!is_cancelled || !is_cancelled()) {
        u64 now = osGetTime();
        if (now >= deadline) break;
        u64 remaining = deadline - now;
        struct pollfd descriptor = { socket, POLLOUT, 0 };
        int ready = poll(&descriptor, 1, remaining > 100 ? 100 : (int)remaining);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) return false;
        if (!ready || !(descriptor.revents & (POLLOUT | POLLERR | POLLHUP | POLLNVAL))) continue;

        int error = 0;
        socklen_t error_length = sizeof error;
        if (getsockopt(socket, SOL_SOCKET, SO_ERROR, &error, &error_length) < 0) return false;
        if (error == 0) return true;
        errno = network_socket_error(error);
        if (errno != EINPROGRESS) return false;
        svcSleepThread(10000000);
    }

    errno = is_cancelled && is_cancelled() ? ECANCELED : ETIMEDOUT;
    return false;
}
