#include "../include/http_server.h"
#include "../include/router.h"
#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

static int send_all(int socket, const char *data, size_t length) {
    while (length) {
        ssize_t written = send(socket, data, length, MSG_NOSIGNAL);
        if (written < 0 && errno == EINTR) continue;
        if (written <= 0) return 0;
        data += written; length -= (size_t)written;
    }
    return 1;
}
int serve_http(const Configuration *configuration) {
    int server = socket(AF_INET, SOCK_STREAM, 0);
    if (server < 0) return 3;
    int enabled = 1;
    setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &enabled, sizeof(enabled));
    struct sockaddr_in address = {0};
    address.sin_family = AF_INET; address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons(configuration->port);
    if (bind(server, (struct sockaddr *)&address, sizeof(address)) || listen(server, 16)) {
        close(server); return 3;
    }
    for (;;) {
        int client = accept(server, NULL, NULL);
        if (client < 0) { if (errno == EINTR) continue; close(server); return 4; }
        struct timeval timeout = {3, 0};
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
        char request[8192] = {0}; size_t used = 0;
        while (used + 1 < sizeof(request) && !strstr(request, "\r\n\r\n")) {
            ssize_t count = recv(client, request + used, sizeof(request) - used - 1, 0);
            if (count <= 0) break;
            used += (size_t)count; request[used] = '\0';
        }
        char method[8], target[2048]; Response response = {0};
        if (strstr(request, "\r\n\r\n") && sscanf(request, "%7s %2047s", method, target) == 2
                && strcmp(method, "GET") == 0) route_request(configuration, target, &response);
        else { response.status = 400; response.content_type = "text/plain"; response.body = "bad-request"; }
        char headers[512];
        const char *reason = response.status == 200 ? "OK" : response.status == 503 ? "Service Unavailable" : "Bad Request";
        int length = snprintf(headers, sizeof(headers),
            "HTTP/1.1 %d %s\r\nContent-Type: %s\r\nContent-Length: %zu\r\nConnection: close\r\n\r\n",
            response.status, reason, response.content_type, strlen(response.body));
        if (length > 0 && (size_t)length < sizeof(headers) && send_all(client, headers, (size_t)length))
            send_all(client, response.body, strlen(response.body));
        close(client);
    }
}
