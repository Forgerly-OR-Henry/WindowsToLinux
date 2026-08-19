cmake_minimum_required(VERSION 3.20)
project(phase3_cmake LANGUAGES C)
add_executable(phase3_cmake src/main.c)
target_compile_features(phase3_cmake PRIVATE c_std_17)
