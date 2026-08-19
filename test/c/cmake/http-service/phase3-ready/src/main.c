#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

int main(void) {
    const int status_code = 200;
    const char *marker = "phase3-live-ok";
    const char *port_text = getenv("PORT");
    if (port_text == NULL) return 2;
    int server = socket(AF_INET, SOCK_STREAM, 0);
    int enabled = 1;
    setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &enabled, sizeof(enabled));
    struct sockaddr_in address = {0};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY);
    address.sin_port = htons((unsigned short)strtoul(port_text, NULL, 10));
    if (bind(server, (struct sockaddr *)&address, sizeof(address)) != 0 || listen(server, 16) != 0) return 3;
    for (;;) {
        int client = accept(server, NULL, NULL);
        if (client < 0) continue;
        char request[1024];
        (void)read(client, request, sizeof(request));
        const char *reason = status_code == 200 ? "OK" : "Service Unavailable";
        char response[2048];
        int length = snprintf(response, sizeof(response),
                "HTTP/1.1 %d %s\r\nContent-Length: %zu\r\nConnection: close\r\n\r\n%s",
                status_code, reason, strlen(marker), marker);
        (void)write(client, response, (size_t)length);
        close(client);
    }
}
