cmake_minimum_required(VERSION 3.22)
project(loopo_native_tests LANGUAGES CXX)

include(CTest)
set(LOOPO_SANITIZER "" CACHE STRING "Optional host sanitizer: address or thread")
set_property(CACHE LOOPO_SANITIZER PROPERTY STRINGS "" address thread)

add_executable(loopo_native_tests native_tests.cpp)
target_compile_features(loopo_native_tests PRIVATE cxx_std_20)
target_include_directories(loopo_native_tests PRIVATE
	${CMAKE_CURRENT_LIST_DIR}/../app/src/main/cpp
	${CMAKE_CURRENT_LIST_DIR}/../app/src/main/cpp/third_party/signalsmith-linear/include
)

if(MSVC)
	target_compile_options(loopo_native_tests PRIVATE /W4 /WX)
else()
	target_compile_options(loopo_native_tests PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()

find_package(Threads REQUIRED)
target_link_libraries(loopo_native_tests PRIVATE Threads::Threads)

if(LOOPO_SANITIZER STREQUAL "address")
	target_compile_options(loopo_native_tests PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
	target_link_options(loopo_native_tests PRIVATE -fsanitize=address,undefined)
elseif(LOOPO_SANITIZER STREQUAL "thread")
	target_compile_options(loopo_native_tests PRIVATE -fsanitize=thread -fno-omit-frame-pointer)
	target_link_options(loopo_native_tests PRIVATE -fsanitize=thread)
elseif(NOT LOOPO_SANITIZER STREQUAL "")
	message(FATAL_ERROR "Unsupported LOOPO_SANITIZER '${LOOPO_SANITIZER}'. Use address or thread.")
endif()

add_test(NAME loopo_native_tests COMMAND loopo_native_tests)
