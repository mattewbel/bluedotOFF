cmake_minimum_required(VERSION 3.22.1)
project(bluedot_adb LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(OPENSSL_NO_ASM ON CACHE BOOL "Portable local client library" FORCE)
# BoringSSL embeds __FILE__ in error records; keep the build machine's folder out of the APK.
add_compile_options("-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=.")
add_subdirectory(third_party/boringssl EXCLUDE_FROM_ALL)
add_library(bluedot_adb SHARED native_adb.cpp
    third_party/aosp-adb/pairing_auth/pairing_auth.cpp
    third_party/aosp-adb/pairing_auth/aes_128_gcm.cpp)
target_include_directories(bluedot_adb PRIVATE compat third_party/aosp-adb/pairing_auth/include)
target_compile_options(bluedot_adb PRIVATE -fvisibility=hidden -Wall -Wextra)
target_link_options(bluedot_adb PRIVATE -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL)
target_link_libraries(bluedot_adb ssl crypto)
