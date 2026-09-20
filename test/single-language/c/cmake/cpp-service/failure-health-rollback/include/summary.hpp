#pragma once
#include <string>
#include <string_view>
#include <vector>
struct Summary {
    std::vector<int> items;
    int total() const;
    std::string json() const;
    static Summary parse(std::string_view query);
};
