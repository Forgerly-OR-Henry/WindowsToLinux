cmake_minimum_required(VERSION 3.25)
project(http_service LANGUAGES CXX)
add_executable(http_service src/main.cpp src/config.cpp src/summary.cpp src/router.cpp src/http_server.cpp)
target_compile_features(http_service PRIVATE cxx_std_20)
