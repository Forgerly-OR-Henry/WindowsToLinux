#include "../include/http_server.hpp"
#include "../include/router.hpp"
#include <arpa/inet.h>
#include <cerrno>
#include <netinet/in.h>
#include <sstream>
#include <stdexcept>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
namespace {
class Socket {
    int descriptor_;
public:
    explicit Socket(int descriptor) : descriptor_(descriptor) {
        if (descriptor < 0) throw std::runtime_error("socket operation failed");
    }
    ~Socket() { close(descriptor_); }
    Socket(const Socket &) = delete;
    Socket &operator=(const Socket &) = delete;
    int get() const { return descriptor_; }
    void send_all(const std::string &message) const {
        std::size_t sent = 0;
        while (sent < message.size()) {
            auto count = send(descriptor_, message.data() + sent, message.size() - sent, MSG_NOSIGNAL);
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) return;
            sent += static_cast<std::size_t>(count);
        }
    }
};
}
void serve_http(const Configuration &configuration) {
    Socket server(socket(AF_INET, SOCK_STREAM, 0));
    int enabled = 1;
    setsockopt(server.get(), SOL_SOCKET, SO_REUSEADDR, &enabled, sizeof(enabled));
    sockaddr_in address{}; address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_ANY); address.sin_port = htons(configuration.port);
    if (bind(server.get(), reinterpret_cast<sockaddr *>(&address), sizeof(address)) || listen(server.get(), 16))
        throw std::runtime_error("bind/listen failed");
    for (;;) {
        int accepted = accept(server.get(), nullptr, nullptr);
        if (accepted < 0 && errno == EINTR) continue;
        Socket client(accepted);
        timeval timeout{3, 0};
        setsockopt(client.get(), SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
        setsockopt(client.get(), SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
        std::string request; char chunk[1024];
        while (request.size() < 8192 && request.find("\r\n\r\n") == std::string::npos) {
            auto size = recv(client.get(), chunk, sizeof(chunk), 0);
            if (size <= 0) break;
            request.append(chunk, static_cast<std::size_t>(size));
        }
        std::istringstream line(request); std::string method, target;
        Response response{400, "text/plain", "bad-request"};
        if (request.size() <= 8192 && request.find("\r\n\r\n") != std::string::npos
                && (line >> method >> target) && method == "GET") response = route_request(configuration, target);
        auto reason = response.status == 200 ? "OK" : response.status == 503 ? "Service Unavailable" : "Bad Request";
        client.send_all("HTTP/1.1 " + std::to_string(response.status) + " " + reason + "\r\nContent-Type: "
            + response.content_type + "\r\nContent-Length: " + std::to_string(response.body.size())
            + "\r\nConnection: close\r\n\r\n" + response.body);
    }
}
