cmake_minimum_required(VERSION 3.22.1)

project(table_tennis_tracker)

add_library(
    table_tennis_tracker
    SHARED
    native-lib.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    table_tennis_tracker
    ${log-lib}
)
