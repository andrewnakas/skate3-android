cmake_minimum_required(VERSION 3.22)
project(skate3_driver_proxy LANGUAGES C CXX)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
  message(FATAL_ERROR "This proxy supports Android arm64-v8a only")
endif()
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_link_options("-Wl,-z,max-page-size=16384")
add_subdirectory(vendor/libadrenotools)

add_library(vulkan SHARED driver_proxy.cpp)
target_compile_definitions(vulkan PRIVATE VK_NO_PROTOTYPES)
target_compile_options(vulkan PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(vulkan PRIVATE adrenotools log dl)
target_link_options(vulkan PRIVATE
  "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
  "-Wl,-Bsymbolic-functions"
  "-Wl,--exclude-libs,ALL")
set_target_properties(vulkan PROPERTIES LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/exports.map")

# Only these three runtime libraries are required for DRIVER_CUSTOM. The
# vendored build also defines optional BCn/file-redirection/GPU-mapping hooks.
install(TARGETS vulkan main_hook hook_impl LIBRARY DESTINATION lib/arm64-v8a)
