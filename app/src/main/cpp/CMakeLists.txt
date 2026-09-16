cmake_minimum_required(VERSION 3.22.1)
project(auravox)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(auravox SHARED
        native-lib.cpp
        AudioEngine.cpp)

target_include_directories(auravox PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(auravox
        oboe::oboe
        android
        log)

# -Ofast enables fast-math, which is safe here: no NaN or Inf is ever a valid
# signal value. Frame pointers stay in for usable stack traces in release.
target_compile_options(auravox PRIVATE
        -Ofast
        -fno-omit-frame-pointer
        -Wall
        -Wextra
        -Wno-unused-parameter)
