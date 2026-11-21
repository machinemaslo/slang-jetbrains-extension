cmake_minimum_required(VERSION 3.20)

set(shader "${SOURCE_ROOT}/${SOURCE_PATH}")
set(directory "generated/${CONFIG}")
set(header_path "${directory}/bindings.hpp")
set(header "${BUILD_ROOT}/${header_path}")
set(cpp_name "${CONFIG}Payload")

file(READ "${BUILD_ROOT}/${directory}/layout.json" reflection)
string(JSON slang_name GET "${reflection}" parameters 0 type elementType name)
string(JSON field_name GET "${reflection}" parameters 0 type elementType fields 0 name)
string(JSON field_type GET "${reflection}" parameters 0 type elementType fields 0 type scalarType)
if(NOT field_type STREQUAL "float32")
    message(FATAL_ERROR "Unexpected reflection type '${field_type}'")
endif()

set(cpp "﻿// Café 🧪: no source-map directives in this header.\r\nstruct ${cpp_name} { float ${field_name}; };\r\n")
file(WRITE "${header}" "${cpp}")

# The legacy lookup can find this, but a v1 lookup must never take it as evidence.
file(WRITE "${BUILD_ROOT}/${directory}/legacy-decoy.hpp"
    "#line 2 \"${shader}\"\nstruct LegacyDecoy { float ignored; };\n")

string(SHA256 shader_key "${SOURCE_PATH}")
file(SHA256 "${shader}" source_hash)
file(SHA256 "${header}" cpp_hash)
file(SHA256 "${SOURCE_ROOT}/generator-input.txt" input_hash)

set(output "${BUILD_ROOT}/${PACK}")
get_filename_component(output_directory "${output}" DIRECTORY)
file(MAKE_DIRECTORY "${output_directory}")
execute_process(
    COMMAND "${PACK_WRITER}" "${output}.tmp" "${CONFIG}"
        "${shader_key}" "${SOURCE_PATH}" "${source_hash}" "${shader}" "${slang_name}"
        "${header_path}" "${cpp_hash}" "${header}" "${cpp_name}" "generator-input.txt" "${input_hash}"
    COMMAND_ERROR_IS_FATAL ANY
)
file(RENAME "${output}.tmp" "${output}")
