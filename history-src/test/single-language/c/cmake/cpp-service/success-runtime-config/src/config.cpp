#include "../include/config.hpp"
#include <charconv>
#include <cstdlib>
#include <stdexcept>
Configuration Configuration::load() {
    const char *raw = std::getenv("PORT");
    const std::string port = raw ? raw : "";
    unsigned int number = 0;
    auto [end, error] = std::from_chars(port.data(), port.data() + port.size(), number);
    if (error != std::errc{} || end != port.data() + port.size() || number < 1 || number > 65535)
        throw std::invalid_argument("Invalid PORT");
    std::string mode = "config";
    const char *label = std::getenv("FIXTURE_LABEL");
    return {static_cast<unsigned short>(number), 200, mode,
            mode == "config" ? (label ? label : "runtime-config-default") : "deployment-smoke-ok"};
}
