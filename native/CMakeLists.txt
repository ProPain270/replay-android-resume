cmake_minimum_required(VERSION 3.22.1)
project(libretro_runtime LANGUAGES CXX)

add_library(retro_runtime_core STATIC src/session_host.cpp src/libretro_core.cpp)
set_target_properties(retro_runtime_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(retro_runtime_core PUBLIC src)
target_compile_features(retro_runtime_core PUBLIC cxx_std_20)
target_compile_options(retro_runtime_core PRIVATE -fno-omit-frame-pointer)

if(ANDROID)
    add_library(retro_runtime SHARED src/runtime_jni.cpp src/android_runtime_bindings.cpp)
    target_compile_features(retro_runtime PRIVATE cxx_std_20)
    target_compile_options(retro_runtime PRIVATE -fno-omit-frame-pointer)
    target_link_libraries(retro_runtime PRIVATE retro_runtime_core android log ${CMAKE_DL_LIBS})
endif()

if(NOT ANDROID)
    add_library(retro_runtime_synthetic_core SHARED tests/synthetic_libretro_core.cpp)
    target_include_directories(retro_runtime_synthetic_core PRIVATE src)
    target_compile_features(retro_runtime_synthetic_core PRIVATE cxx_std_20)
    set_target_properties(retro_runtime_synthetic_core PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON
    )

    add_executable(retro_runtime_harness tests/libretro_boundary_harness.cpp)
    target_include_directories(retro_runtime_harness PRIVATE src)
    target_compile_features(retro_runtime_harness PRIVATE cxx_std_20)
    target_link_libraries(retro_runtime_harness PRIVATE retro_runtime_core ${CMAKE_DL_LIBS})

    add_executable(session_host_harness tests/session_host_harness.cpp)
    target_include_directories(session_host_harness PRIVATE src)
    target_compile_features(session_host_harness PRIVATE cxx_std_20)
    target_link_libraries(session_host_harness PRIVATE retro_runtime_core ${CMAKE_DL_LIBS})

    enable_testing()
    add_test(
        NAME retro_runtime_harness
        COMMAND retro_runtime_harness $<TARGET_FILE:retro_runtime_synthetic_core>
    )
    set_tests_properties(retro_runtime_harness PROPERTIES
        PASS_REGULAR_EXPRESSION "retro_runtime_harness: PASS"
    )
    add_test(NAME session_host_harness COMMAND session_host_harness)
    set_tests_properties(session_host_harness PROPERTIES
        PASS_REGULAR_EXPRESSION "session_host_harness: PASS"
    )
endif()
