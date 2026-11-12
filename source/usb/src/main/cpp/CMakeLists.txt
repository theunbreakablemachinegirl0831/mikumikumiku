cmake_minimum_required(VERSION 3.22.1)
project(mmmusb CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(mmmusb SHARED usb_iso.cpp)
target_compile_options(mmmusb PRIVATE -Wall -Wextra)
