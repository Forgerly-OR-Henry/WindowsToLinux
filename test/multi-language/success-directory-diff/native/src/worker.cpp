#include "worker.hpp"
#include <chrono>
#include <filesystem>
#include <iostream>
#include <nlohmann/json.hpp>
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif
using nlohmann::json;
namespace fs = std::filesystem;
static std::string utf8(const fs::path &p) {
    auto s = p.generic_u8string();
    return std::string(reinterpret_cast<const char *>(s.data()), s.size());
}
static bool linked(const fs::path &p) {
    bool result = fs::is_symlink(fs::symlink_status(p));
#ifdef _WIN32
    const DWORD attributes = GetFileAttributesW(p.c_str());
    if (attributes == INVALID_FILE_ATTRIBUTES)
        throw std::runtime_error("cannot inspect file attributes");
    result = result || (attributes & FILE_ATTRIBUTE_REPARSE_POINT);
#endif
    return result;
}
int run(const std::vector<std::string> &args) {
    uint64_t sequence = 0, files = 0, skipped = 0, problems = 0, visited = 0;
    auto emit = [&](json record) {
        record["protocolVersion"] = 2;
        record["component"] = "cpp-scan";
        record["sequence"] = sequence++;
        std::cout << record.dump() << '\n';
        if (!std::cout)
            throw std::runtime_error("output closed");
    };
    try {
        if (args.size() != 4 || args[0] != "--root" || args[2] != "--max-files") {
            std::cerr << "--root DIRECTORY --max-files N required\n";
            return 2;
        }
        const auto limit = std::stoull(args[3]);
        if (!limit || limit > 1000000)
            return 2;
        const auto root = fs::path(std::u8string(args[1].begin(), args[1].end()));
        if (!fs::is_directory(root) || linked(root)) {
            std::cerr << "root must be a real directory, not a link/reparse point\n";
            return 3;
        }
        emit({{"type", "start"}});
        std::vector<std::pair<fs::path, size_t>> pending{{root, 0}};
        while (!pending.empty()) {
            auto [directory, depth] = pending.back();
            pending.pop_back();
            std::error_code ec;
            fs::directory_iterator it(directory, ec), end;
            if (ec) {
                problems++;
                emit(
                    {{"type", "problem"}, {"path", utf8(directory.lexically_relative(root))}, {"error", ec.message()}});
                continue;
            }
            while (it != end) {
                const auto path = it->path();
                const auto relative = utf8(path.lexically_relative(root));
                if (++visited > limit * 4 + 1000)
                    throw std::runtime_error("directory entry limit exceeded");
                try {
                    if (linked(path)) {
                        skipped++;
                        emit({{"type", "skip"}, {"path", relative}, {"reason", "symbolic-link-or-reparse-point"}});
                    } else if (it->is_directory()) {
                        if (depth >= 128)
                            throw std::runtime_error("directory depth exceeds 128");
                        pending.emplace_back(path, depth + 1);
                    } else if (it->is_regular_file()) {
                        if (++files > limit)
                            throw std::runtime_error("file count exceeds --max-files");
#ifdef _WIN32
                        WIN32_FILE_ATTRIBUTE_DATA attributes{};
                        if (!GetFileAttributesExW(path.c_str(), GetFileExInfoStandard, &attributes))
                            throw std::runtime_error("cannot read file metadata");
                        const uint64_t ticks =
                            (static_cast<uint64_t>(attributes.ftLastWriteTime.dwHighDateTime) << 32) |
                            attributes.ftLastWriteTime.dwLowDateTime;
                        const int64_t modified_ns = (static_cast<int64_t>(ticks) - 116444736000000000LL) * 100;
                        const uint64_t size =
                            (static_cast<uint64_t>(attributes.nFileSizeHigh) << 32) | attributes.nFileSizeLow;
#else
                        const auto modified = fs::file_time_type::clock::to_sys(it->last_write_time());
                        const auto modified_ns =
                            std::chrono::duration_cast<std::chrono::nanoseconds>(modified.time_since_epoch()).count();
                        const auto size = it->file_size();
#endif
                        emit({{"type", "entry"}, {"path", relative}, {"size", size}, {"modifiedNs", modified_ns}});
                    } else {
                        problems++;
                        emit({{"type", "problem"}, {"path", relative}, {"error", "unsupported file type"}});
                    }
                } catch (const std::exception &e) {
                    problems++;
                    emit({{"type", "problem"}, {"path", relative}, {"error", e.what()}});
                }
                if (files > limit)
                    throw std::runtime_error("file count exceeds --max-files");
                it.increment(ec);
                if (ec) {
                    problems++;
                    emit({{"type", "problem"}, {"path", relative}, {"error", ec.message()}});
                    break;
                }
            }
        }
        emit({{"type", "end"},
              {"messages", sequence + 1},
              {"files", files},
              {"skipped", skipped},
              {"problems", problems},
              {"complete", problems == 0}});
        return problems ? 3 : 0;
    } catch (const std::exception &e) {
        std::cerr << e.what() << '\n';
        return 3;
    }
}
