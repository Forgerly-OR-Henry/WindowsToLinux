#pragma once
#include <cstdint>
#include <map>
#include <nlohmann/json.hpp>
#include <string>

struct Record {
    std::string time, level, service, message;
};
bool valid_time(const std::string &value);
Record parse_record(const std::string &line);
std::string error_group(const std::string &message);
struct Aggregate {
    uint64_t lines = 0, matched = 0, invalid_count = 0;
    size_t max_keys;
    std::map<std::string, uint64_t> levels{{"DEBUG", 0}, {"INFO", 0}, {"WARN", 0}, {"ERROR", 0}}, minutes,
        services, errors;
    nlohmann::json invalid = nlohmann::json::array();
    explicit Aggregate(size_t limit) : max_keys(limit) {}
    void add(const Record &record);
    nlohmann::json result() const;
};
