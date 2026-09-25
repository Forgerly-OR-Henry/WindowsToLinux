#include "../include/config.hpp"
#include "../include/http_server.hpp"
#include <exception>
#include <iostream>
int main() {
    try {
        serve_http(Configuration::load());
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return 2;
    }
}
