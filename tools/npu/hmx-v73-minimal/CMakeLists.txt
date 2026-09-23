cmake_minimum_required(VERSION 3.22.2)
project(hmx-v73-minimal C)

include(${HEXAGON_SDK_ROOT}/build/cmake/hexagon_fun.cmake)
include_directories(${HEXAGON_SDK_ROOT}/incs ${HEXAGON_SDK_ROOT}/incs/stddef ${CMAKE_CURRENT_BINARY_DIR})

add_library(hmx-probe-v73 SHARED hmx_probe_dsp.c hmx_probe_skel.c)
build_idl(hmx_probe.idl hmx-probe-v73)
