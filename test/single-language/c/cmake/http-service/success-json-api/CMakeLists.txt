cmake_minimum_required(VERSION 3.25)
project(http_service LANGUAGES C)
add_executable(http_service src/main.c src/config.c src/summary.c src/router.c src/http_server.c)
target_compile_features(http_service PRIVATE c_std_11)
