#include "worker.h"
#ifdef _WIN32
#include <stdlib.h>
#include <windows.h>
int wmain(int argc, wchar_t **argv) {
    char **args = calloc((size_t)argc, sizeof(char *));
    if (!args)
        return 4;
    for (int i = 0; i < argc; i++) {
        int n = WideCharToMultiByte(CP_UTF8, 0, argv[i], -1, NULL, 0, NULL, NULL);
        args[i] = malloc((size_t)n);
        if (!args[i]) {
            for (int j = 0; j < i; j++)
                free(args[j]);
            free(args);
            return 4;
        }
        WideCharToMultiByte(CP_UTF8, 0, argv[i], -1, args[i], n, NULL, NULL);
    }
    int result = run(argc, args);
    for (int i = 0; i < argc; i++)
        free(args[i]);
    free(args);
    return result;
}
#else
int main(int argc, char **argv) {
    return run(argc, argv);
}
#endif
