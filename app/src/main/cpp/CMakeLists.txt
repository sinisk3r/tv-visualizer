cmake_minimum_required(VERSION 3.21)
project(visualizer LANGUAGES C CXX)

# projectM 4 (LGPL-2.1) from the submodule, built as its own shared library (libprojectM-4.so) so it can be
# swapped, as the LGPL asks. No playlist library: the app keeps its own preset list in Java.
set(PROJECTM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/projectm)
set(ENABLE_PLAYLIST OFF CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_PROJECTM_EVAL OFF CACHE BOOL "" FORCE)
set(ENABLE_SYSTEM_GLM OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
add_subdirectory(${PROJECTM_DIR} projectm)

add_library(visualizer SHARED native.cpp)
target_link_libraries(visualizer PRIVATE libprojectM::projectM GLESv3 log android)
