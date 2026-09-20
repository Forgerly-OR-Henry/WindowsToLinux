#pragma once
#include <string>
struct Configuration {
    unsigned short port;
    int status;
    std::string mode;
    std::string label;
    static Configuration load();
};
