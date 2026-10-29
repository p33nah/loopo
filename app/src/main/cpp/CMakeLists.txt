cmake_minimum_required(VERSION 3.22.1)
project(loopo_native LANGUAGES CXX)

add_library(loopo_native SHARED native_bootstrap.cpp native_playback.cpp PlaybackEngine.cpp)
target_compile_features(loopo_native PRIVATE cxx_std_20)
find_package(oboe REQUIRED CONFIG)
target_link_libraries(loopo_native PRIVATE oboe::oboe android log)
