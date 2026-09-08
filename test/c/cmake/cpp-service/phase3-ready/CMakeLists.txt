cmake_minimum_required(VERSION 3.25)
project(phase3_cmake LANGUAGES CXX)
add_executable(phase3_cmake src/main.cpp)
target_compile_features(phase3_cmake PRIVATE cxx_std_20)
