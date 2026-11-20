cmake_minimum_required(VERSION 3.20)
project(ReflectionFixture LANGUAGES CXX)

add_executable(reflect reflection-generator.cc)
target_compile_features(reflect PRIVATE cxx_std_17)
target_include_directories(reflect PRIVATE "${SLANG_SDK}/include")
find_library(slang_library NAMES slang-compiler slang PATHS "${SLANG_SDK}/lib" NO_DEFAULT_PATH REQUIRED)
target_link_libraries(reflect PRIVATE "${slang_library}")
set_target_properties(reflect PROPERTIES RUNTIME_OUTPUT_DIRECTORY "$<1:${CMAKE_BINARY_DIR}/bin>")

if(WIN32)
    file(GLOB runtimes "${SLANG_SDK}/bin/*.dll")
    foreach(runtime IN LISTS runtimes)
        add_custom_command(TARGET reflect POST_BUILD COMMAND "${CMAKE_COMMAND}" -E copy_if_different
                "${runtime}" "$<TARGET_FILE_DIR:reflect>")
    endforeach()
endif()
