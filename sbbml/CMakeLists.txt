project(SBBML)
cmake_minimum_required(VERSION 3.22.1)

set(CMAKE_VERBOSE_MAKEFILE on)

get_filename_component(SAMPLE_SRC_DIR  ${CMAKE_SOURCE_DIR}  ABSOLUTE)

if (ANDROID_ABI MATCHES "^armeabi-v7a$")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfloat-abi=softfp -mfpu=neon")
elseif(ANDROID_ABI MATCHES "^arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -ftree-vectorize")
endif()

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DSTANDALONE_DEMO_LIB \
                    -std=c++11 -fno-exceptions -fno-rtti -O2 -Wno-narrowing \
                    -fPIE")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} \
                              -Wl,--allow-multiple-definition \
                              -Wl,--whole-archive -fPIE -v")

file(GLOB_RECURSE sbbml_sources ${SAMPLE_SRC_DIR}/src/main/cpp/*.*)

add_library(sbbml SHARED ${sbbml_sources})

target_include_directories(sbbml PRIVATE ${CMAKE_SOURCE_DIR})

target_link_libraries(sbbml
                      android
                      log
                      jnigraphics
                      m
                      atomic
                      z)