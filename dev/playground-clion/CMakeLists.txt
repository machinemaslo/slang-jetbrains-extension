cmake_minimum_required(VERSION 3.20)
project(Playground LANGUAGES CXX)

set(slang_tools "${CMAKE_CURRENT_SOURCE_DIR}/.idea/slang-tools.path")
if(EXISTS "${slang_tools}")
    file(STRINGS "${slang_tools}" slang_tool_directory LIMIT_COUNT 1 ENCODING UTF-8)
endif()
find_program(playground_slangc NAMES slangc HINTS "${slang_tool_directory}")
get_filename_component(slang_program_directory "${playground_slangc}" DIRECTORY)
find_package(slang 2026.22 CONFIG REQUIRED HINTS
    "${slang_program_directory}/.." "${slang_program_directory}/../..")

set(REFLECTION_HELPER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../src/main/resources/reflection"
    CACHE PATH "Directory containing jb-slanglsp-reflection-meta.hpp")
add_executable(playground_generator reflection/GenerateBindings.cpp)
target_link_libraries(playground_generator PRIVATE slang::slang)
target_include_directories(playground_generator PRIVATE "${REFLECTION_HELPER_DIR}")
set_target_properties(playground_generator PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED YES)

function(add_playground target debug_type debug_sources release_sources)
    set(shader "${CMAKE_CURRENT_SOURCE_DIR}/reflection.slang")
    set(generated "${CMAKE_CURRENT_BINARY_DIR}/generated/$<CONFIG>/${target}")
    set(header "${generated}/bindings.hpp")
    set(type "$<IF:$<CONFIG:Release>,${debug_type}Release,${debug_type}>")
    set(sources "${CMAKE_CURRENT_SOURCE_DIR}/$<IF:$<CONFIG:Release>,${release_sources},${debug_sources}>")
    set(pack_directory "${CMAKE_CURRENT_BINARY_DIR}/.jb-slanglsp/reflection/v1")

    if(CMAKE_CONFIGURATION_TYPES)
        set(pack_name "")
        foreach(config IN LISTS CMAKE_CONFIGURATION_TYPES)
            string(SHA256 variant "cmake\n${target}\n${config}\n")
            string(APPEND pack_name "$<$<CONFIG:${config}>:${variant}.pack>")
        endforeach()
    else()
        string(SHA256 variant "cmake\n${target}\n${CMAKE_BUILD_TYPE}\n")
        set(pack_name "${variant}.pack")
    endif()
    set(pack "${pack_directory}/${pack_name}")

    set(generator_command "$<TARGET_FILE:playground_generator>")
    if(WIN32)
        list(PREPEND generator_command "${CMAKE_COMMAND}" -E env
            "PATH=$<TARGET_FILE_DIR:slang::slang>$<SEMICOLON>$ENV{PATH}")
    endif()
    add_custom_command(
        OUTPUT "${pack}"
        BYPRODUCTS "${header}" "${generated}/reflection.spv"
        COMMAND ${generator_command}
            "${CMAKE_CURRENT_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}" cmake "${target}" "$<CONFIG>" ""
            "${shader}" "${header}" "${type}"
        DEPENDS "${shader}" playground_generator
        VERBATIM
    )
    # Keep the evaluated pack visible in the File API, including generator-expression outputs.
    add_custom_target(${target}_bindings DEPENDS "${pack}" SOURCES "${pack}")
    add_executable(${target} "${sources}/main.cpp" "${sources}/material.cpp")
    add_dependencies(${target} ${target}_bindings)
    target_include_directories(${target} PRIVATE "${generated}")
    target_compile_features(${target} PRIVATE cxx_std_17)
endfunction()

add_playground(playground MaterialConstants reflection reflection/release)
add_playground(reflection_preview PreviewConstants reflection/preview reflection/preview-release)
