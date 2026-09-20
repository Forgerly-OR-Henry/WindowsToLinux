#pragma once
#include "config.hpp"
#include <string_view>
struct Response { int status; std::string content_type; std::string body; };
Response route_request(const Configuration &configuration, std::string_view target);
