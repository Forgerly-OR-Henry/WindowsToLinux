#include "log.hpp"
#include <chrono>
#include <regex>
#include <stdexcept>

bool valid_time(const std::string &value) {
    static const std::regex syntax(R"(^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$)");
    if (!std::regex_match(value, syntax))
        return false;
    const auto day =
        std::chrono::year_month_day(std::chrono::year(std::stoi(value.substr(0, 4))),
                                    std::chrono::month(static_cast<unsigned>(std::stoi(value.substr(5, 2)))),
                                    std::chrono::day(static_cast<unsigned>(std::stoi(value.substr(8, 2)))));
    return day.ok() && std::stoi(value.substr(11, 2)) < 24 && std::stoi(value.substr(14, 2)) < 60 &&
           std::stoi(value.substr(17, 2)) < 60;
}
Record parse_record(const std::string &line) {
    Record record;
    if (!line.empty() && line.front() == '{') {
        const auto value = nlohmann::json::parse(line);
        record = {value.at("time").get<std::string>(), value.at("level").get<std::string>(),
                  value.at("service").get<std::string>(), value.at("message").get<std::string>()};
    } else {
        static const std::regex pattern(R"(^([^ ]+) ([A-Z]+) \[([^\]]+)\] (.*)$)");
        std::smatch match;
        if (!std::regex_match(line, match, pattern))
            throw std::invalid_argument("expected TIME LEVEL [service] message or JSON object");
        record = {match[1], match[2], match[3], match[4]};
        (void)nlohmann::json(line).dump(); // Reject non-UTF-8 before aggregating it.
    }
    if (record.level == "WARNING")
        record.level = "WARN";
    if (record.level == "ERR")
        record.level = "ERROR";
    if (!valid_time(record.time) ||
        !(record.level == "DEBUG" || record.level == "INFO" || record.level == "WARN" || record.level == "ERROR") ||
        record.service.empty() || record.service.size() > 100 || record.message.size() > 8192)
        throw std::invalid_argument("invalid time, level, service or message length");
    return record;
}
std::string error_group(const std::string &message) {
    std::string result;
    bool digits = false;
    for (const unsigned char c : message) {
        if (c >= '0' && c <= '9') {
            if (!digits)
                result += '#';
            digits = true;
        } else {
            result += static_cast<char>(c);
            digits = false;
        }
    }
    return result;
}
void Aggregate::add(const Record &record) {
    matched++;
    levels[record.level]++;
    minutes[record.time.substr(0, 16)]++;
    services[record.service]++;
    if (record.level == "ERROR")
        errors[record.service + ": " + error_group(record.message)]++;
    if (minutes.size() + services.size() + errors.size() > max_keys)
        throw std::runtime_error("aggregation keys exceed --max-keys");
}
nlohmann::json Aggregate::result() const {
    return {{"lines", lines},   {"matched", matched}, {"invalidCount", invalid_count}, {"invalid", invalid},
            {"levels", levels}, {"minutes", minutes}, {"services", services},          {"errors", errors}};
}
