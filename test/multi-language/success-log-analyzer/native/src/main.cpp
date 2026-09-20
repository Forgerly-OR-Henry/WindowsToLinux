#include "worker.hpp"
#ifdef _WIN32
#include <windows.h>
int wmain(int argc, wchar_t **argv) {
    std::vector<std::string> args;
    for (int i = 1; i < argc; i++) {
        int n = WideCharToMultiByte(CP_UTF8, 0, argv[i], -1, nullptr, 0, nullptr, nullptr);
        if (n <= 0)
            return 2;
        std::string s(static_cast<size_t>(n), '\0');
        WideCharToMultiByte(CP_UTF8, 0, argv[i], -1, s.data(), n, nullptr, nullptr);
        s.pop_back();
        args.push_back(s);
    }
    return run(args);
}
#else
int main(int argc, char **argv) {
    return run(std::vector<std::string>(argv + 1, argv + argc));
}
#endif
