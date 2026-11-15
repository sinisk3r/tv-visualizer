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
# Our changes to projectM, as patch files in patches/projectm (applied in name order to the submodule, skipped if
# already applied). Each one says where it came from; check them again when the submodule moves.
file(GLOB PROJECTM_PATCHES ${CMAKE_CURRENT_SOURCE_DIR}/../../../../patches/projectm/*.patch)
list(SORT PROJECTM_PATCHES)
foreach(patch ${PROJECTM_PATCHES})
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${patch})
    execute_process(COMMAND git apply --reverse --check ${patch} WORKING_DIRECTORY ${PROJECTM_DIR}
                    RESULT_VARIABLE not_applied OUTPUT_QUIET ERROR_QUIET)
    if(not_applied)
        execute_process(COMMAND git apply ${patch} WORKING_DIRECTORY ${PROJECTM_DIR} RESULT_VARIABLE failed)
        if(failed)
            message(FATAL_ERROR "projectM patch does not apply: ${patch}")
        endif()
        message(STATUS "Applied projectM patch: ${patch}")
    endif()
endforeach()
add_subdirectory(${PROJECTM_DIR} projectm)

add_library(visualizer SHARED native.cpp)
target_link_libraries(visualizer PRIVATE libprojectM::projectM GLESv3 EGL log android)
