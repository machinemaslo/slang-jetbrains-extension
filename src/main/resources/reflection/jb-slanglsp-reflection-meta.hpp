#ifndef JB_SLANGLSP_REFLECTION_META_HPP
#define JB_SLANGLSP_REFLECTION_META_HPP

// Increment for a new bundled helper release, independently of the pack format version.
#define JB_SLANGLSP_REFLECTION_META_VERSION 3

/*
 * Welcome to Slang-to-C++ navigation!
 *
 * Include this optional C++17 header in your existing reflection generator.
 * Keep your Slang compilation, reflection, and C++ emission exactly as they are.
 * After closing your generated files, call write_reflection_meta() once with the
 * declarations your generator actually connected. This header writes one indexed
 * binary pack per build variant. It never runs a compiler, edits a shader, rewrites
 * C++, adds #line directives, or changes your build scripts.
 *
 * QUICK START
 *
 *   namespace m = jb_slanglsp::reflection;
 *   const auto source = m::read_binary(shader_path);
 *   // Compile exactly `source` and generate your C++ as usual. Record the byte
 *   // positions of the declaration identifiers, not the positions of the
 *   // `struct` keywords. In an std::string, size() before appending the name
 *   // gives its position. For an std::ostringstream use tellp().
 *
 *   const m::Build build{project_root, build_dir, "cmake", "renderer", "Debug"};
 *   const auto result = m::write_reflection_meta(build, {
 *       {shader_path, source, {
 *           {
 *               {"Material", m::ByteRange::at(slang_name_offset, "Material")},
 *               {{{m::Root::build, header_path},
 *                 {"GpuMaterial", m::ByteRange::at(cpp_name_offset, "GpuMaterial")}}}
 *           }
 *       }}
 *   });
 *   if (!result)
 *       std::cerr << "Reflection metadata: " << result.error << '\n';
 *
 * Do not guess an origin from a short name or matching layout. Use the location
 * of the declaration your compiler or generator actually resolved. The origin
 * may be in an imported file, not in the shader entry-point file. If you cannot
 * prove the origin, omit that binding.
 *
 * BUILD INTEGRATION
 *
 * Supply build identity from the build, not from the shader backend (for example,
 * SPIR-V is not a CMake target). For CMake, use the canonical build target name,
 * the configuration generation directory, and the actual $<CONFIG>. For MSBuild,
 * use the project-relative .vcxproj path as the target, the absolute resolved
 * $(IntDir) as the build directory, and the evaluated project $(Configuration)
 * and $(Platform). The pack is a build output/byproduct at
 * .jb-slanglsp/reflection/v1/<variant-key>.pack under the build directory.
 * The key is lowercase hex SHA-256 of system, target, configuration, and platform
 * joined by newlines (the last field is empty for CMake). Declare the shader and
 * generator dependencies in the build.
 *
 * One writer owns each (build directory, build identity) pack. Supply the
 * complete source list on every call: omitted sources are removed, and an empty
 * list clears the pack. If several steps generate for the same variant, collect
 * their records in one publishing step. Independent processes must not append
 * to or overwrite the same pack. One Slang declaration with several C++
 * destinations is one Binding with several Destination entries.
 *
 * Call the writer even when you skip rewriting an identical generated header.
 * Source positions can change while the C++ bytes remain identical. If you
 * render to a buffer but use a Windows text-mode stream, pass that buffer in
 * Destination::rendered_bytes. The helper translates LF to CRLF only after
 * checking that this is the only difference in the final file.
 *
 * Commit this header with your generator. The generated .jb-slanglsp directory
 * belongs to the build tree, not to version control.
 */

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <limits>
#include <map>
#include <optional>
#include <set>
#include <stdexcept>
#include <string>
#include <string_view>
#include <tuple>
#include <utility>
#include <vector>

namespace jb_slanglsp::reflection {

namespace fs = std::filesystem;

/// The root against which a metadata path is expressed.
enum class Root { project, build };

/// A path relative to the declared root, or an absolute path inside that root.
struct File {
    Root root;
    fs::path path;
};

/// Version 1 build identity. All views passed to this API must survive the call.
struct Build {
    fs::path workspace_root;       ///< Root of workspace-relative source paths.
    fs::path build_directory;      ///< Existing directory that owns the metadata.
    std::string_view system;       ///< "cmake" or "msbuild".
    std::string_view target;       ///< CMake target or workspace-relative .vcxproj.
    std::string_view configuration; ///< Actual build configuration, possibly empty for CMake.
    std::string_view platform = {}; ///< Empty for CMake; e.g. "x64" for MSBuild.
};

/// Zero-based, end-exclusive UTF-8 byte offsets, including any BOM and CR bytes.
struct ByteRange {
    std::size_t begin;
    std::size_t end;

    /// Build a range when you already know the identifier's starting byte.
    static ByteRange at(std::size_t offset, std::string_view name) {
        if (name.size() > (std::numeric_limits<std::size_t>::max)() - offset) {
            throw std::out_of_range("Reflection metadata byte range overflows");
        }
        return {offset, offset + name.size()};
    }
};

struct Declaration {
    std::string_view name; ///< Exact unqualified identifier at this location.
    ByteRange range;        ///< Location of the identifier, not its enclosing declaration.
};

struct Destination {
    File file;                ///< Final generated C++ file, already closed by the caller.
    Declaration declaration;  ///< Range in final bytes, or in rendered_bytes if supplied.
    std::optional<std::string_view> rendered_bytes = std::nullopt;
    ///< Optional pre-write buffer. Only identical bytes or LF-to-CRLF are accepted.
};

struct Binding {
    Declaration slang;             ///< Declaration in the enclosing Shader's source bytes.
    std::vector<Destination> cpp; ///< One or more distinct generated declarations.
};

struct Input {
    File file;              ///< Additional import, generator input, or build receipt.
    std::string_view bytes; ///< Exact snapshot used to produce the output; may be binary.
};

struct Shader {
    fs::path path;                   ///< Source file, relative to workspace_root or absolute within it.
    std::string_view source_bytes;   ///< Exact UTF-8 bytes used by the generator, not a later reread.
    std::vector<Binding> bindings;  ///< An empty list explicitly records no C++ mappings.
    std::vector<Input> inputs = {}; ///< Other inputs the reader needs to validate freshness.
};

struct Result {
    std::string error; ///< Empty on success; otherwise a diagnostic for the generator to log.

    explicit operator bool() const noexcept {
        return error.empty();
    }
};

/// Optional helper for snapshotting a file before generation. Throws on an I/O error.
inline std::string read_binary(const fs::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        throw std::runtime_error("Cannot open reflection input: " + path.string());
    }
    input.exceptions(std::ios::badbit);
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

namespace detail {

inline void require(bool accepted, const char* message) {
    if (!accepted) {
        throw std::runtime_error(message);
    }
}

inline bool valid_utf8(std::string_view text) {
    for (std::size_t i = 0; i < text.size();) {
        const auto lead = static_cast<unsigned char>(text[i++]);
        if (lead < 0x80) {
            continue;
        }
        const unsigned count = lead >= 0xc2 && lead <= 0xdf ? 1 :
            lead >= 0xe0 && lead <= 0xef ? 2 : lead >= 0xf0 && lead <= 0xf4 ? 3 : 0;
        if (count == 0 || count > text.size() - i) {
            return false;
        }
        const auto second = static_cast<unsigned char>(text[i]);
        if ((lead == 0xe0 && second < 0xa0) || (lead == 0xed && second >= 0xa0) ||
            (lead == 0xf0 && second < 0x90) || (lead == 0xf4 && second >= 0x90)) {
            return false;
        }
        for (unsigned j = 0; j < count; ++j) {
            if ((static_cast<unsigned char>(text[i++]) & 0xc0) != 0x80) {
                return false;
            }
        }
    }
    return true;
}

inline bool identifier_start(unsigned char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c >= 0x80;
}

inline bool identifier_byte(unsigned char c) {
    return identifier_start(c) || (c >= '0' && c <= '9');
}

inline void check_name(std::string_view bytes, Declaration declaration) {
    const auto [begin, end] = declaration.range;
    const auto name = declaration.name;
    require(!name.empty() && valid_utf8(name) && identifier_start(name.front()) &&
                std::all_of(name.begin(), name.end(), identifier_byte),
            "Expected an unqualified declaration identifier");
    const bool starts_after_bom = begin == 3 && bytes.substr(0, 3) == "\xef\xbb\xbf";
    require(begin < end && end <= bytes.size() && bytes.substr(begin, end - begin) == name &&
                (begin == 0 || starts_after_bom || !identifier_byte(bytes[begin - 1])) &&
                (end == bytes.size() || !identifier_byte(bytes[end])),
            "Declaration byte range does not select the complete identifier");
}

} // namespace detail

/**
 * Optional exact-location helper. Convert a physical one-based line and one-based
 * UTF-8 byte column into a declaration range, checking the identifier. CRLF does
 * not change line numbering. A BOM counts in the file's offsets, not in line 1's
 * columns. Convert columns first if your compiler reports UTF-16 or display
 * columns. This helper never searches for a name or guesses a declaration site.
 */
inline ByteRange name_at(std::string_view bytes, std::size_t line, std::size_t column, std::string_view name) {
    detail::require(line > 0 && column > 0, "Declaration line and column must be one-based");
    std::size_t begin = bytes.substr(0, 3) == "\xef\xbb\xbf" ? 3 : 0;
    for (std::size_t i = 1; i < line; ++i) {
        const auto newline = bytes.find('\n', begin);
        detail::require(newline != std::string_view::npos, "Declaration line is outside the file");
        begin = newline + 1;
    }
    const auto end = (std::min)(bytes.find('\n', begin), bytes.size());
    detail::require(column - 1 < end - begin, "Declaration column is outside the line");
    const auto result = ByteRange::at(begin + column - 1, name);
    detail::require(result.end <= end, "Declaration identifier crosses a line");
    detail::check_name(bytes, {name, result});
    return result;
}

namespace detail {

using Digest = std::array<unsigned char, 32>;

// SHA-256 (FIPS 180-4). Private to the writer; no crypto library or tool is needed.
inline Digest sha256(std::string_view data) {
    constexpr std::array<std::uint32_t, 64> k{
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    };
    std::array<std::uint32_t, 8> state{
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    };
    const auto rotate = [](std::uint32_t value, unsigned count) {
        return (value >> count) | (value << (32 - count));
    };
    const auto block = [&](const unsigned char* bytes) {
        std::array<std::uint32_t, 64> w{};
        for (unsigned i = 0; i < 16; ++i) {
            for (unsigned j = 0; j < 4; ++j) {
                w[i] = (w[i] << 8) | bytes[i * 4 + j];
            }
        }
        for (unsigned i = 16; i < 64; ++i) {
            const auto x = w[i - 15];
            const auto y = w[i - 2];
            w[i] = w[i - 16] + (rotate(x, 7) ^ rotate(x, 18) ^ (x >> 3)) +
                w[i - 7] + (rotate(y, 17) ^ rotate(y, 19) ^ (y >> 10));
        }
        auto [a, b, c, d, e, f, g, h] = state;
        for (unsigned i = 0; i < 64; ++i) {
            const auto t1 = h + (rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25)) +
                ((e & f) ^ (~e & g)) + k[i] + w[i];
            const auto t2 = (rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22)) +
                ((a & b) ^ (a & c) ^ (b & c));
            h = g;
            g = f;
            f = e;
            e = d + t1;
            d = c;
            c = b;
            b = a;
            a = t1 + t2;
        }
        const std::array<std::uint32_t, 8> result{a, b, c, d, e, f, g, h};
        for (unsigned i = 0; i < state.size(); ++i) {
            state[i] += result[i];
        }
    };
    const auto* bytes = reinterpret_cast<const unsigned char*>(data.data());
    const auto complete = data.size() - data.size() % 64;
    for (std::size_t offset = 0; offset < complete; offset += 64) {
        block(bytes + offset);
    }
    std::array<unsigned char, 128> tail{};
    const auto rest = data.size() - complete;
    if (rest != 0) {
        std::copy_n(bytes + complete, rest, tail.begin());
    }
    tail[rest] = 0x80;
    const unsigned padded = rest < 56 ? 64 : 128;
    const auto bits = static_cast<std::uint64_t>(data.size()) * 8;
    for (unsigned i = 0; i < 8; ++i) {
        tail[padded - 1 - i] = static_cast<unsigned char>(bits >> (i * 8));
    }
    block(tail.data());
    if (padded == 128) {
        block(tail.data() + 64);
    }
    Digest digest{};
    std::size_t i = 0;
    for (const auto value : state) {
        for (int shift = 24; shift >= 0; shift -= 8) {
            digest[i++] = static_cast<unsigned char>(value >> shift);
        }
    }
    return digest;
}

inline std::string hex_digest(const Digest& digest) {
    std::string hex;
    hex.reserve(digest.size() * 2);
    for (const auto byte : digest) {
        hex += "0123456789abcdef"[byte >> 4];
        hex += "0123456789abcdef"[byte & 0xf];
    }
    return hex;
}

template<std::size_t Size>
inline void append_bytes(std::string& output, const std::array<unsigned char, Size>& bytes) {
    output.append(reinterpret_cast<const char*>(bytes.data()), bytes.size());
}

template<std::size_t Size>
inline void append_le(std::string& output, std::uint64_t value) {
    std::array<unsigned char, Size> bytes{};
    for (auto& byte : bytes) {
        byte = static_cast<unsigned char>(value);
        value >>= 8;
    }
    append_bytes(output, bytes);
}

inline void append_count(std::string& output, std::size_t count) {
    require(count <= (std::numeric_limits<std::uint32_t>::max)(), "Reflection metadata count exceeds v1 limits");
    append_le<4>(output, count);
}

inline void append_text(std::string& output, std::string_view text) {
    append_count(output, text.size());
    output += text;
}

inline void check_text(std::string_view value) {
    require(valid_utf8(value) && std::none_of(value.begin(), value.end(), [](unsigned char c) {
        return c < 0x20 || c == 0x7f;
    }), "Build identity and paths must be UTF-8 without control characters");
}

inline void check_relative(std::string_view path) {
    check_text(path);
    require(!path.empty() && path.front() != '/' && path.find('\\') == std::string_view::npos &&
                !(path.size() >= 2 && path[1] == ':'),
            "Expected a portable root-relative path");
    while (true) {
        const auto slash = path.find('/');
        const auto part = path.substr(0, slash);
        require(!part.empty() && part != "." && part != "..", "Path escapes its declared root");
        if (slash == std::string_view::npos) {
            return;
        }
        path.remove_prefix(slash + 1);
    }
}

inline std::string path_text(const fs::path& path) {
    const auto utf8 = path.generic_u8string();
    return {utf8.begin(), utf8.end()};
}

inline fs::path utf8_path(const std::string& path) {
#if defined(__cpp_char8_t)
    return fs::path(std::u8string(path.begin(), path.end()));
#else
    return fs::u8path(path);
#endif
}

inline std::string relative_to(const fs::path& path, const fs::path& root) {
    auto relative = path_text(path.lexically_relative(root));
    check_relative(relative);
    return relative;
}

inline char root_tag(Root root) {
    switch (root) {
    case Root::project:
        return '\0';
    case Root::build:
        return '\1';
    }
    throw std::runtime_error("Unknown reflection metadata root");
}

inline void append_declaration(std::string& record, Declaration declaration) {
    append_le<8>(record, declaration.range.begin);
    append_text(record, declaration.name);
}

struct Snapshot {
    fs::path physical_path;
    std::string bytes;
    Digest hash;
    bool utf8;
    std::optional<std::string_view> rendered;
    std::vector<std::size_t> inserted_cr; // Positions in the rendered buffer.

    Snapshot(fs::path path, std::string contents)
        : physical_path(std::move(path)), bytes(std::move(contents)), hash(sha256(bytes)), utf8(valid_utf8(bytes)) {}

    ByteRange final_range(Declaration declaration, std::optional<std::string_view> buffer) {
        if (!buffer) {
            return declaration.range;
        }
        if (!rendered) {
            const auto text = *buffer;
            std::size_t j = 0;
            for (std::size_t i = 0; i < text.size(); ++i, ++j) {
                if (text[i] == '\n' && j < bytes.size() && bytes[j] == '\r') {
                    inserted_cr.push_back(i);
                    ++j;
                }
                require(j < bytes.size() && text[i] == bytes[j],
                        "Generated C++ does not match the rendered buffer");
            }
            require(j == bytes.size(), "Generated C++ does not match the rendered buffer");
            rendered = buffer;
        } else {
            require((rendered->data() == buffer->data() && rendered->size() == buffer->size()) ||
                        *rendered == *buffer,
                    "Conflicting rendered buffers for the same C++ file");
        }
        check_name(*buffer, declaration);
        const auto translate = [&](std::size_t offset) {
            return offset + static_cast<std::size_t>(
                std::lower_bound(inserted_cr.begin(), inserted_cr.end(), offset) - inserted_cr.begin());
        };
        return {translate(declaration.range.begin), translate(declaration.range.end)};
    }
};

class Files {
public:
    const fs::path project;
    const fs::path build;

    explicit Files(const Build& context)
        : project(absolute_directory(context.workspace_root)),
          build(absolute_directory(context.build_directory)),
          real_project(fs::canonical(project)),
          real_build(fs::canonical(build)) {}

    using Key = std::pair<Root, std::string>;

    Key key(const File& file) const {
        root_tag(file.root);
        const auto& base = file.root == Root::project ? project : build;
        const auto path = (base / file.path).lexically_normal();
        return {file.root, relative_to(path, base)};
    }

    Snapshot& read(const Key& key) {
        if (const auto found = snapshots.find(key); found != snapshots.end()) {
            return found->second;
        }
        const bool in_project = key.first == Root::project;
        const auto path = fs::canonical((in_project ? project : build) / utf8_path(key.second));
        relative_to(path, in_project ? real_project : real_build);
        require(fs::is_regular_file(path), "Reflection input is not a regular file");
        return snapshots.try_emplace(key, path, read_binary(path)).first->second;
    }

    void check_pack_path(const fs::path& path) const {
        const auto parent = fs::weakly_canonical(path.parent_path());
        relative_to(parent, real_build);
        const auto real_path = fs::weakly_canonical(parent / path.filename());
        for (const auto& entry : snapshots) {
            require(entry.second.physical_path != real_path, "Reflection pack would replace a source, input, or output");
        }
    }

private:
    const fs::path real_project;
    const fs::path real_build;
    std::map<Key, Snapshot> snapshots;

    static fs::path absolute_directory(const fs::path& path) {
        require(!path.empty() && fs::is_directory(path), "Expected an existing project or build directory");
        // Preserve workspace spelling in the index key; check real paths separately for containment.
        return fs::absolute(path).lexically_normal();
    }
};

inline void append_file(std::string& record, const Files::Key& key, const Snapshot& file) {
    record += root_tag(key.first);
    append_text(record, key.second);
    append_bytes(record, file.hash);
}

struct Record {
    Digest key;
    std::string bytes;
};

struct Pack {
    fs::path path;
    std::string header;
    std::vector<Record> records;
};

inline void atomic_write(const Pack& pack) {
    // A suffix for the temporary file, not part of the protocol. Exactly one
    // writer may own a pack; readers only ever open its final name.
    static std::atomic<unsigned long long> serial{0};
    auto temporary = pack.path;
    temporary += ".tmp-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()) +
        "-" + std::to_string(serial.fetch_add(1, std::memory_order_relaxed));
    require(fs::symlink_status(temporary).type() == fs::file_type::not_found,
            "Reflection metadata temporary file already exists");
    try {
        std::ofstream output(temporary, std::ios::binary);
        output.exceptions(std::ios::failbit | std::ios::badbit);
        const auto write = [&](std::string_view bytes) {
            require(bytes.size() <= static_cast<std::size_t>((std::numeric_limits<std::streamsize>::max)()),
                    "Reflection metadata is too large to write");
            output.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
        };
        write(pack.header);
        for (const auto& record : pack.records) {
            write(record.bytes);
        }
        output.close();
        fs::rename(temporary, pack.path);
    } catch (...) {
        std::error_code ignored;
        fs::remove(temporary, ignored);
        throw;
    }
}

inline void check_build(const Build& build) {
    require(build.system == "cmake" || build.system == "msbuild", "Unknown reflection metadata build system");
    require(!build.target.empty(), "Reflection metadata needs a build target");
    check_text(build.target);
    check_text(build.configuration);
    check_text(build.platform);
    if (build.system == "cmake") {
        require(build.platform.empty(), "CMake build identity does not have a platform field");
    } else {
        check_relative(build.target);
        require(!build.configuration.empty() && !build.platform.empty(),
                "MSBuild metadata needs a project configuration and platform");
    }
}

inline Pack prepare(const Build& context, const std::vector<Shader>& shaders) {
    check_build(context);
    const auto variant = hex_digest(sha256(std::string(context.system) + "\n" + std::string(context.target) +
        "\n" + std::string(context.configuration) + "\n" + std::string(context.platform)));
    Files files(context);
    Pack pack{files.build / ".jb-slanglsp" / "reflection" / "v1" / (variant + ".pack"),
              std::string("JBSLRPK\0", 8), {}};
    append_le<4>(pack.header, 1);
    append_count(pack.header, shaders.size());
    append_text(pack.header, context.system);
    append_text(pack.header, context.target);
    append_text(pack.header, context.configuration);
    append_text(pack.header, context.platform);
    pack.records.reserve(shaders.size());

    for (const auto& shader : shaders) {
        const auto source_key = files.key({Root::project, shader.path});
        const auto& source = files.read(source_key);
        require(source.utf8 && source.bytes == shader.source_bytes,
                "Slang source is not UTF-8 or changed since generation");
        auto& record = pack.records.emplace_back(Record{sha256(source_key.second), {}}).bytes;
        append_text(record, source_key.second);
        append_bytes(record, source.hash);

        std::string input_records;
        std::set<Files::Key> seen_inputs;
        for (const auto& input : shader.inputs) {
            const auto key = files.key(input.file);
            const auto& bytes = files.read(key);
            require(bytes.bytes == input.bytes, "Additional reflection input changed since generation");
            if (seen_inputs.insert(key).second) {
                append_file(input_records, key, bytes);
            }
        }
        append_count(record, seen_inputs.size());
        record += input_records;

        append_count(record, shader.bindings.size());
        std::vector<const Binding*> ordered;
        ordered.reserve(shader.bindings.size());
        for (const auto& binding : shader.bindings) {
            ordered.push_back(&binding);
        }
        std::sort(ordered.begin(), ordered.end(), [](const Binding* a, const Binding* b) {
            return a->slang.range.begin < b->slang.range.begin;
        });
        std::size_t previous_end = 0;
        for (const auto* binding : ordered) {
            check_name(source.bytes, binding->slang);
            require(binding->slang.range.begin >= previous_end, "Duplicate or overlapping Slang declaration");
            require(!binding->cpp.empty(), "A binding needs at least one C++ declaration");
            previous_end = binding->slang.range.end;
            append_declaration(record, binding->slang);
            append_count(record, binding->cpp.size());

            std::set<std::tuple<fs::path, std::size_t, std::size_t>> destinations;
            for (const auto& destination : binding->cpp) {
                const auto key = files.key(destination.file);
                auto& output = files.read(key);
                require(output.utf8, "Generated C++ must be UTF-8");
                const Declaration physical{
                    destination.declaration.name,
                    output.final_range(destination.declaration, destination.rendered_bytes),
                };
                check_name(output.bytes, physical);
                require(destinations.emplace(output.physical_path, physical.range.begin, physical.range.end).second,
                        "Duplicate C++ destination");
                append_file(record, key, output);
                append_declaration(record, physical);
            }
        }
    }

    std::sort(pack.records.begin(), pack.records.end(), [](const Record& a, const Record& b) {
        return a.key < b.key;
    });
    constexpr std::uint64_t index_entry_size = 32 + 8 + 8;
    const auto max_offset = (std::numeric_limits<std::uint64_t>::max)();
    const auto index_size = index_entry_size * pack.records.size();
    require(pack.header.size() <= max_offset - index_size, "Reflection pack is too large");
    auto offset = pack.header.size() + index_size;
    const Digest* previous = nullptr;
    for (const auto& record : pack.records) {
        require(!previous || *previous < record.key, "Duplicate shader path or digest in reflection pack");
        require(record.bytes.size() <= max_offset - offset, "Reflection pack is too large");
        append_bytes(pack.header, record.key);
        append_le<8>(pack.header, offset);
        append_le<8>(pack.header, record.bytes.size());
        offset += record.bytes.size();
        previous = &record.key;
    }
    files.check_pack_path(pack.path);
    return pack;
}

} // namespace detail

/**
 * Validate the entire batch, then atomically publish one v1 binary pack.
 *
 * Example: write_reflection_meta(build, {shader1, shader2}); callers that
 * accumulate records can pass a std::vector<Shader> directly. This synchronous
 * call does not keep any string_views or buffers after it returns. Each call
 * replaces the complete variant pack, including when the list is empty. On
 * failure the previous pack remains. Existing generated C++ and source files
 * are only read, never rewritten.
 *
 * A false result does not require failing your generator. Log result.error or
 * choose to make metadata mandatory in your own build. The pack carries file
 * hashes so the IDE reader can reject stale positions from a previous build.
 */
inline Result write_reflection_meta(const Build& build, const std::vector<Shader>& shaders) {
    try {
        const auto pack = detail::prepare(build, shaders);
        fs::create_directories(pack.path.parent_path());
        detail::atomic_write(pack);
        return {};
    } catch (const std::exception& error) {
        return {error.what()};
    }
}

} // namespace jb_slanglsp::reflection

#endif // JB_SLANGLSP_REFLECTION_META_HPP
