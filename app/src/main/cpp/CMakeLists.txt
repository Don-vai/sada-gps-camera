
cmake_minimum_required(VERSION 3.22.1)

project("sada-gps-camera")

add_library(
    sada-native
    SHARED
    native-lib.cpp
)

find_library(
    log-lib
    log
)

target_compile_features(sada-native PRIVATE cxx_std_17)

target_link_libraries(
    sada-native
    ${log-lib}
)
