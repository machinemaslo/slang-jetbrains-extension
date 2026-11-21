cmake_minimum_required(VERSION 3.20)
project(ReflectionMetadataContract LANGUAGES CXX)

set(FIXTURE_SHADER "shaders/material.slang" CACHE STRING "Workspace-relative Slang source")
set(shader "${CMAKE_CURRENT_SOURCE_DIR}/${FIXTURE_SHADER}")
set(generated "${CMAKE_CURRENT_BINARY_DIR}/generated/$<CONFIG>")
set(header "${generated}/bindings.hpp")
set(decoy "${generated}/legacy-decoy.hpp")
set(configurations Debug Release)

foreach(config IN LISTS configurations)
    string(SHA256 variant_key "cmake\nhost\n${config}\n")
    set(path_${config} ".jb-slanglsp/reflection/v1/${variant_key}.pack")
endforeach()
set(pack "$<IF:$<CONFIG:Debug>,${path_Debug},${path_Release}>")

add_executable(fixture_pack_writer WriteFixturePack.cpp)
target_compile_features(fixture_pack_writer PRIVATE cxx_std_17)

add_custom_command(
    OUTPUT "${header}" "${decoy}" "${CMAKE_CURRENT_BINARY_DIR}/${pack}"
    BYPRODUCTS "${generated}/layout.json" "${generated}/material.spv"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${generated}"
    COMMAND "${SLANGC}" "${shader}" -entry reflectMaterial -target spirv
        -reflection-json "${generated}/layout.json" -o "${generated}/material.spv"
    COMMAND "${CMAKE_COMMAND}"
        "-DSOURCE_ROOT=${CMAKE_CURRENT_SOURCE_DIR}"
        "-DBUILD_ROOT=${CMAKE_CURRENT_BINARY_DIR}"
        "-DSOURCE_PATH=${FIXTURE_SHADER}"
        "-DCONFIG=$<CONFIG>"
        "-DPACK=${pack}"
        "-DPACK_WRITER=$<TARGET_FILE:fixture_pack_writer>"
        -P "${CMAKE_CURRENT_SOURCE_DIR}/WriteFixtureMetadata.cmake"
    DEPENDS fixture_pack_writer "${shader}" "${CMAKE_CURRENT_SOURCE_DIR}/generator-input.txt"
        "${CMAKE_CURRENT_SOURCE_DIR}/WriteFixtureMetadata.cmake"
    VERBATIM
)

add_custom_target(material_bindings DEPENDS "${header}" "${decoy}" "${CMAKE_CURRENT_BINARY_DIR}/${pack}")
add_executable(host main.cpp)
add_dependencies(host material_bindings)
target_include_directories(host PRIVATE "${generated}")
target_compile_definitions(host PRIVATE "HOST_TYPE=$<IF:$<CONFIG:Debug>,DebugPayload,ReleasePayload>")
target_compile_features(host PRIVATE cxx_std_17)
