cmake_minimum_required(VERSION 3.18.1)
project("hermesnative")

# Capstone 디스어셈블러
find_library(log-lib log)

add_library(hermesnative SHARED
    native-lib.cpp
    disassembler.cpp
    elf_parser.cpp
    dex_parser.cpp
    apk_parser.cpp
    string_extractor.cpp
    pattern_matcher.cpp)

find_package(capstone REQUIRED CONFIG)
target_link_libraries(hermesnative ${log-lib} capstone::capstone)
