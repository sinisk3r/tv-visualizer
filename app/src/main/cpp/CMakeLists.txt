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
# Same library names in debug builds (projectM adds "d"), so System.loadLibrary works there too: the emulator
# UI tests run against the debug build.
set(ENABLE_DEBUG_POSTFIX OFF CACHE BOOL "" FORCE)
# Our changes to projectM, as patch files in patches/projectm, applied in name order to the submodule. Each one says
# where it came from; check them again when the submodule moves. Later patches change lines of earlier ones, so:
# if the last patch is already applied, all are; otherwise reset projectM's src/ and apply them all in order.
# The lock keeps two ABIs that configure at once from patching at the same time.
file(GLOB PROJECTM_PATCHES ${CMAKE_CURRENT_SOURCE_DIR}/../../../../patches/projectm/*.patch)
list(SORT PROJECTM_PATCHES)
if(PROJECTM_PATCHES)
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${PROJECTM_PATCHES})
    file(LOCK ${PROJECTM_DIR}/../.projectm-patch.lock GUARD FILE)
    list(GET PROJECTM_PATCHES -1 last_patch)
    execute_process(COMMAND git apply --reverse --check ${last_patch} WORKING_DIRECTORY ${PROJECTM_DIR}
                    RESULT_VARIABLE not_applied OUTPUT_QUIET ERROR_QUIET)
    if(not_applied)
        execute_process(COMMAND git checkout -- src WORKING_DIRECTORY ${PROJECTM_DIR})
        foreach(patch ${PROJECTM_PATCHES})
            execute_process(COMMAND git apply ${patch} WORKING_DIRECTORY ${PROJECTM_DIR} RESULT_VARIABLE failed)
            if(failed)
                message(FATAL_ERROR "projectM patch does not apply: ${patch}")
            endif()
            message(STATUS "Applied projectM patch: ${patch}")
        endforeach()
    endif()
    file(LOCK ${PROJECTM_DIR}/../.projectm-patch.lock RELEASE)
endif()
add_subdirectory(${PROJECTM_DIR} projectm)

add_library(visualizer SHARED native.cpp)
target_link_libraries(visualizer PRIVATE libprojectM::projectM GLESv3 EGL log android)
