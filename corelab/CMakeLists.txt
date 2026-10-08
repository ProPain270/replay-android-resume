cmake_minimum_required(VERSION 3.20)
project(corelab LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(corelab_dummy SHARED src/dummy_core.cpp)
target_include_directories(corelab_dummy PRIVATE include)
set_target_properties(corelab_dummy PROPERTIES
    OUTPUT_NAME corelab_dummy
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

add_library(corelab_dummy_no_persistence SHARED src/dummy_core.cpp)
target_include_directories(corelab_dummy_no_persistence PRIVATE include)
target_compile_definitions(corelab_dummy_no_persistence PRIVATE CORELAB_DUMMY_NO_PERSISTENCE=1)
set_target_properties(corelab_dummy_no_persistence PROPERTIES
    OUTPUT_NAME corelab_dummy_no_persistence
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

add_executable(corelab_runner src/corelab_runner.cpp)
target_include_directories(corelab_runner PRIVATE include)
if(UNIX)
    target_link_libraries(corelab_runner PRIVATE ${CMAKE_DL_LIBS})
endif()

enable_testing()
add_test(
    NAME corelab_dummy_certification
    COMMAND corelab_runner
        --profile ${CMAKE_CURRENT_SOURCE_DIR}/manifest/dummy.profile
        --core $<TARGET_FILE:corelab_dummy>
        --report-dir ${CMAKE_CURRENT_BINARY_DIR}/report
)
set_tests_properties(corelab_dummy_certification PROPERTIES
    PASS_REGULAR_EXPRESSION "CORELAB PASS"
)

add_test(
    NAME corelab_dummy_not_applicable_certification
    COMMAND corelab_runner
        --profile ${CMAKE_CURRENT_SOURCE_DIR}/manifest/dummy-no-persistence.profile
        --core $<TARGET_FILE:corelab_dummy_no_persistence>
        --report-dir ${CMAKE_CURRENT_BINARY_DIR}/report-no-persistence
)
set_tests_properties(corelab_dummy_not_applicable_certification PROPERTIES
    PASS_REGULAR_EXPRESSION "CORELAB PASS"
)
