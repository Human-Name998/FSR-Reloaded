cmake_minimum_required(VERSION 3.20)
project(fsrvk VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(WIN32)
    add_compile_definitions(VK_USE_PLATFORM_WIN32_KHR)
endif()

# Vulkan headers needed at compile time for Vk types used in vulkan_device.cpp
# and vulkan_memory.cpp.  At LINK time / runtime, vulkan-1.dll is delay-loaded
# so the DLL loads successfully even when Vulkan is not installed.  D3D12-only
# paths (FSR4 pool, Fsr4Context) work without Vulkan at all.
find_package(Vulkan REQUIRED)
include_directories(${Vulkan_INCLUDE_DIRS})

find_package(JNI QUIET)
if(NOT JNI_FOUND)
    if(NOT DEFINED JAVA_HOME)
        set(JAVA_HOME "C:/Program Files/Java/jdk-25.0.2" CACHE PATH "Java JDK path")
    endif()
    include_directories("${JAVA_HOME}/include" "${JAVA_HOME}/include/win32")
else()
    include_directories(${JNI_INCLUDE_DIRS})
endif()

add_library(fsrvk SHARED
    vulkan_device.cpp
    vulkan_memory.cpp
    d3d12_device.cpp
    d3d12_shared_resources.cpp
    fsr4_context.cpp
    jni_bridge.cpp
)

target_include_directories(fsrvk PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
if(WIN32)
    # Delay-load vulkan-1.dll so fsrvk.dll loads WITHOUT the Vulkan runtime.
    # We still link against Vulkan::Vulkan for the import lib (symbol resolution
    # at link time), but the actual vulkan-1.dll isn't loaded until a Vulkan
    # function is first called.  D3D12-only paths (FSR4 pool, Fsr4Context) never
    # touch Vulkan functions and work independently.
    target_link_libraries(fsrvk PRIVATE Vulkan::Vulkan d3d12.lib dxgi.lib delayimp.lib)
    target_link_options(fsrvk PRIVATE "/DELAYLOAD:vulkan-1.dll")
else()
    target_link_libraries(fsrvk PRIVATE Vulkan::Vulkan d3d12.lib dxgi.lib)
endif()

# AMD FSR API unified headers
target_include_directories(fsrvk PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/AMD-FSR-API/include
)

# POST_BUILD: copy both SDK DLLs next to fsrvk.dll
add_custom_command(TARGET fsrvk POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/AMD-FSR-API/lib/amd_fidelityfx_loader_dx12.dll
        $<TARGET_FILE_DIR:fsrvk>/
)
add_custom_command(TARGET fsrvk POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/AMD-FSR-API/lib/amd_fsr4_api.dll
        $<TARGET_FILE_DIR:fsrvk>/
)
