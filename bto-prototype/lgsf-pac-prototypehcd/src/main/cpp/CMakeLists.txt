cmake_minimum_required(VERSION 3.16)
project(pac-camera-jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
set(IPX_SDK_ROOT "/opt/IpxCameraSDK-1.5.0.83"
    CACHE PATH "Root of the Imperx Camera SDK installation")

set(IPX_INC "${IPX_SDK_ROOT}/inc")
set(IPX_LIB "${IPX_SDK_ROOT}/lib/Linux64_x64")

# ---------------------------------------------------------------------------
# JNI headers — use CMake's FindJNI or fall back to JAVA_HOME
# ---------------------------------------------------------------------------
find_package(JNI REQUIRED)

# ---------------------------------------------------------------------------
# Shared library target
# ---------------------------------------------------------------------------
add_library(pac-camera-jni SHARED PacCameraJni.cpp)

target_include_directories(pac-camera-jni PRIVATE
    ${IPX_INC}
    ${JNI_INCLUDE_DIRS}
)

target_link_libraries(pac-camera-jni PRIVATE
    "${IPX_LIB}/libIpxCameraApi.so"
)

# Embed the Imperx lib directory in the RPATH so the JVM can find the SDK .so
# files without requiring LD_LIBRARY_PATH at runtime.
set_target_properties(pac-camera-jni PROPERTIES
    INSTALL_RPATH "${IPX_LIB}"
    BUILD_WITH_INSTALL_RPATH TRUE
)

# ---------------------------------------------------------------------------
# Install
# ---------------------------------------------------------------------------
install(TARGETS pac-camera-jni
        LIBRARY DESTINATION lib)
