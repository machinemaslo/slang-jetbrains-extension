#include "jb-slanglsp-reflection-meta.hpp"

#include <cassert>
#include <iostream>

namespace m = jb_slanglsp::reflection;

static void write_file(const m::fs::path& path, std::string_view bytes) {
    std::ofstream output(path, std::ios::binary);
    output.exceptions(std::ios::badbit | std::ios::failbit);
    output.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
}

static std::string crlf(std::string_view text) {
    std::string result;
    for (const char c : text) {
        if (c == '\n') {
            result += '\r';
        }
        result += c;
    }
    return result;
}

int main(int argc, char** argv) {
    if (argc != 3) {
        return 2;
    }
    const m::fs::path workspace(argv[1]);
    const m::fs::path build_directory(argv[2]);
    const m::Build build{workspace, build_directory, "cmake", "render target", "Debug"};
    const auto output = build_directory / "bindings.hpp";
    const std::string rendered =
        "// Non-ASCII UTF-8 before the declaration: caf\xc3\xa9.\n"
        "struct HostPaint { float shade; };\n"
        "struct HostPaintView { float shade; };\n";
    const auto cpp_bytes = crlf(rendered);
    write_file(output, cpp_bytes);

    const std::string initial_source = "// non-ASCII: caf\xc3\xa9\r\nstruct Paint { float shade; };\r\n";
    const std::string moved_source = "\r\n" + initial_source;
    const auto shader = workspace / "material.slang";
    const auto unused_shader = workspace / "caf\xc3\xa9 no output.slang";
    const auto retired_shader = workspace / "retired.slang";
    const auto input = workspace / "build receipt.bin";
    const std::string receipt(130, '\0');
    write_file(shader, initial_source);
    write_file(unused_shader, "// no C++ counterparts\n");
    write_file(retired_shader, "// removed from the next pack\n");
    write_file(input, receipt);

    const m::Binding mapping{
        {"Paint", m::name_at(initial_source, 2, 8, "Paint")},
        {
            {{m::Root::build, output},
             {"HostPaint", m::ByteRange::at(rendered.find("HostPaint"), "HostPaint")}, rendered},
            {{m::Root::build, "bindings.hpp"},
             {"HostPaintView", m::name_at(rendered, 3, 8, "HostPaintView")}, rendered},
        }
    };
    const auto unused = m::read_binary(unused_shader);
    const auto retired = m::read_binary(retired_shader);
    const m::Shader empty{unused_shader, unused, {}};
    m::Shader material{shader, initial_source, {mapping}, {{{m::Root::project, input}, receipt}}};
    auto result = m::write_reflection_meta(build, {material, empty, {retired_shader, retired, {}}});
    if (!result) {
        std::cerr << result.error << '\n';
        return 1;
    }

    // Keep the generated header byte-for-byte identical, but shift the source.
    write_file(shader, moved_source);
    assert(!m::write_reflection_meta(build, {material}));
    material.source_bytes = moved_source;
    material.bindings.front().slang.range = m::name_at(moved_source, 3, 8, "Paint");
    result = m::write_reflection_meta(build, {empty, material});
    if (!result) {
        std::cerr << result.error << '\n';
        return 1;
    }

    const auto metadata = build_directory / ".jb-slanglsp" / "reflection" / "v1";
    const auto pack = m::fs::directory_iterator(metadata)->path();
    const auto committed = m::read_binary(pack);
    assert(m::write_reflection_meta(build, {material, empty}));
    assert(m::read_binary(pack) == committed);

    auto duplicate = material;
    duplicate.bindings.push_back(duplicate.bindings.front());
    assert(!m::write_reflection_meta(build, {empty, duplicate}));
    assert(!m::write_reflection_meta(build, {material, material}));

    auto colliding_input = material;
    colliding_input.inputs.push_back({{m::Root::build, pack}, committed});
    assert(!m::write_reflection_meta(build, {colliding_input}));

    auto wrong_output = material;
    wrong_output.bindings.front().cpp.front().file.path = shader;
    assert(!m::write_reflection_meta(build, {wrong_output}));

    auto wrong_buffer = material;
    wrong_buffer.bindings.front().cpp.front().rendered_bytes = "different bytes";
    assert(!m::write_reflection_meta(build, {wrong_buffer}));

    const auto link = build_directory / "escaped.hpp";
    m::fs::create_symlink(shader, link);
    auto escaped_output = material;
    escaped_output.bindings.front().cpp.front().file.path = link;
    assert(!m::write_reflection_meta(build, {escaped_output}));
    assert(m::read_binary(pack) == committed);

    // An empty batch replaces a populated variant without affecting another variant.
    auto release = build;
    release.configuration = "Release";
    assert(m::write_reflection_meta(release, {material}));
    assert(m::write_reflection_meta(release, {}));
    assert(m::read_binary(pack) == committed);
    assert(m::read_binary(output) == cpp_bytes);
    assert(m::read_binary(shader) == moved_source);
}
