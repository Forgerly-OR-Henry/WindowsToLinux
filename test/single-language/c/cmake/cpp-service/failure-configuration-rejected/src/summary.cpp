#include "../include/summary.hpp"
#include <charconv>
#include <numeric>
#include <stdexcept>
static int hex(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    throw std::invalid_argument("invalid-values");
}
Summary Summary::parse(std::string_view query) {
    while (!query.empty()) {
        auto end = query.find('&'); auto field = query.substr(0, end);
        if (field == "values") throw std::invalid_argument("invalid-values");
        if (field.starts_with("values=")) {
            std::string decoded;
            auto value = field.substr(7);
            for (std::size_t i = 0; i < value.size(); ++i) {
                char c = value[i];
                if (c == '%') {
                    if (i + 2 >= value.size()) throw std::invalid_argument("invalid-values");
                    c = static_cast<char>(hex(value[i + 1]) * 16 + hex(value[i + 2])); i += 2;
                }
                if ((c < '0' || c > '9') && c != ',') throw std::invalid_argument("invalid-values");
                decoded += c;
            }
            Summary result;
            std::string_view remaining(decoded);
            for (;;) {
                auto comma = remaining.find(','); auto token = remaining.substr(0, comma);
                int item = 0;
                auto [last, error] = std::from_chars(token.data(), token.data() + token.size(), item);
                if (token.empty() || error != std::errc{} || last != token.data() + token.size()
                        || item < 0 || item > 10000 || result.items.size() == 20)
                    throw std::invalid_argument("invalid-values");
                result.items.push_back(item);
                if (comma == std::string_view::npos) return result;
                remaining.remove_prefix(comma + 1);
            }
        }
        if (end == std::string_view::npos) break;
        query.remove_prefix(end + 1);
    }
    return Summary{{1, 2, 3}};
}
int Summary::total() const { return std::accumulate(items.begin(), items.end(), 0); }
std::string Summary::json() const {
    std::string result = "{\"status\":\"ok\",\"items\":[";
    for (std::size_t i = 0; i < items.size(); ++i) result += (i ? "," : "") + std::to_string(items[i]);
    return result + "],\"total\":" + std::to_string(total()) + "}";
}
