#include "../include/router.hpp"
#include "../include/summary.hpp"
#include <stdexcept>
Response route_request(const Configuration &configuration, std::string_view target) {
    if (configuration.status == 503) return {503, "text/plain; charset=utf-8", configuration.label};
    auto separator = target.find('?');
    bool summary_route = target.substr(0, separator) == "/api/summary";
    if (summary_route || configuration.mode == "json") {
        try {
            auto query = summary_route && separator != std::string_view::npos ? target.substr(separator + 1) : "";
            return {200, "application/json; charset=utf-8", Summary::parse(query).json()};
        } catch (const std::invalid_argument &) { return {400, "text/plain; charset=utf-8", "invalid-values"}; }
    }
    return {200, "text/plain; charset=utf-8", configuration.label};
}
