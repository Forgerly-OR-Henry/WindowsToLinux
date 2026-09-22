#include "worker.hpp"
#include "log.hpp"
#include <array>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <set>

int run(const std::vector<std::string> &args) {
    try {
        std::map<std::string, std::string> options;
        const std::set<std::string> keys{"--input",   "--level",   "--from",     "--to",
                                         "--service", "--keyword", "--max-keys", "--max-bytes"};
        for (size_t i = 0; i < args.size(); i += 2) {
            if (i + 1 >= args.size() || !keys.count(args[i]) || options.count(args[i]))
                throw std::invalid_argument("invalid or duplicate option");
            options[args[i]] = args[i + 1];
        }
        if (!options.count("--input"))
            throw std::invalid_argument("--input required");
        for (const auto &key : {"--from", "--to"})
            if (!options[key].empty() && !valid_time(options[key]))
                throw std::invalid_argument("invalid UTC time");
        if (!options["--from"].empty() && !options["--to"].empty() && options["--from"] > options["--to"])
            throw std::invalid_argument("reversed time range");
        if (!options["--level"].empty() &&
            !std::set<std::string>{"DEBUG", "INFO", "WARN", "ERROR"}.count(options["--level"]))
            throw std::invalid_argument("invalid level");
        auto number = [&](const std::string &key, uint64_t fallback, uint64_t maximum) {
            if (!options.count(key))
                return fallback;
            const auto &v = options[key];
            if (v.empty() || v.find_first_not_of("0123456789") != std::string::npos)
                throw std::invalid_argument("invalid numeric limit");
            auto n = std::stoull(v);
            if (!n || n > maximum)
                throw std::invalid_argument("limit out of range");
            return static_cast<uint64_t>(n);
        };
        const auto max_bytes = number("--max-bytes", 268435456, 1073741824);
        Aggregate aggregate(number("--max-keys", 10000, 100000));
        const auto &input = options["--input"];
        std::ifstream file(std::filesystem::path(std::u8string(input.begin(), input.end())), std::ios::binary);
        if (!file) {
            std::cerr << "input file unavailable\n";
            return 3;
        }
        std::array<char, 65538> buffer{};
        uint64_t bytes = 0;
        while (true) {
            file.getline(buffer.data(), static_cast<std::streamsize>(buffer.size()));
            const auto consumed = file.gcount();
            if (!consumed && file.eof())
                break;
            bytes += static_cast<uint64_t>(consumed);
            if (bytes > max_bytes || file.bad() || (file.fail() && !file.eof())) {
                std::cerr << "file read failed or line/file size limit exceeded at line " << aggregate.lines + 1
                          << '\n';
                return 3;
            }
            std::string line(buffer.data(), static_cast<size_t>(consumed) - (file.eof() ? 0 : 1));
            if (!line.empty() && line.back() == '\r')
                line.pop_back();
            aggregate.lines++;
            Record record;
            try {
                record = parse_record(line);
            } catch (const std::exception &e) {
                aggregate.invalid_count++;
                if (aggregate.invalid.size() < 20)
                    aggregate.invalid.push_back(
                        {{"line", aggregate.lines}, {"reason", std::string(e.what()).substr(0, 160)}});
                continue;
            }
            if ((!options["--level"].empty() && record.level != options["--level"]) ||
                (!options["--service"].empty() && record.service != options["--service"]) ||
                (!options["--from"].empty() && record.time < options["--from"]) ||
                (!options["--to"].empty() && record.time > options["--to"]) ||
                (!options["--keyword"].empty() && record.message.find(options["--keyword"]) == std::string::npos))
                continue;
            aggregate.add(record);
        }
        auto result = aggregate.result();
        result["protocolVersion"] = 2;
        result["component"] = "cpp-log";
        result["type"] = "summary";
        result["sequence"] = 1;
        const auto output = result.dump();
        if (output.size() > 8 * 1024 * 1024 - 512)
            throw std::runtime_error("aggregation output exceeds 8 MiB");
        std::cout << "{\"protocolVersion\":2,\"component\":\"cpp-log\",\"type\":\"start\",\"sequence\":0}\n"
                  << output << '\n';
        std::cout << nlohmann::json{{"protocolVersion", 2}, {"component", "cpp-log"}, {"type", "end"},
                                    {"sequence", 2},        {"messages", 3},          {"records", aggregate.lines}}
                         .dump()
                  << '\n';
        return 0;
    } catch (const std::invalid_argument &e) {
        std::cerr << e.what() << '\n';
        return 2;
    } catch (const std::exception &e) {
        std::cerr << e.what() << '\n';
        return 4;
    }
}
