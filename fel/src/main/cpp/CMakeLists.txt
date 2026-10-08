cmake_minimum_required(VERSION 3.22.1)
project(felnative LANGUAGES CXX)

# libdovi 3.3.2 (quietvoid/dovi_tool C API, MIT), prebuilt static library per ABI.
set(LIBDOVI_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../libdovi")
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(LIBDOVI_ABI_DIR "${LIBDOVI_ROOT}/android-arm64")
else()
    message(FATAL_ERROR "Mittyfin builds arm64-v8a only (got ${ANDROID_ABI})")
endif()

add_library(dovi_static STATIC IMPORTED)
set_target_properties(dovi_static PROPERTIES IMPORTED_LOCATION "${LIBDOVI_ABI_DIR}/lib/libdovi.a")

add_library(felnative SHARED fel_jni.cpp)
target_compile_features(felnative PRIVATE cxx_std_17)
target_compile_options(felnative PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_include_directories(felnative PRIVATE "${LIBDOVI_ABI_DIR}/include")
target_link_options(felnative PRIVATE "-Wl,-z,max-page-size=16384")
find_library(log-lib log)
target_link_libraries(felnative dovi_static ${log-lib})
