cmake_minimum_required(VERSION 3.22.1)
project(loopo_native LANGUAGES CXX)

add_library(loopo_native SHARED native_bootstrap.cpp)
target_compile_features(loopo_native PRIVATE cxx_std_20)
