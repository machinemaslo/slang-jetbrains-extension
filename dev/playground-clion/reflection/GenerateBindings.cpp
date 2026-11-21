#include <jb-slanglsp-reflection-meta.hpp>

#include <slang.h>
#include <slang-com-ptr.h>

#include <iostream>
#include <sstream>

using Slang::ComPtr;
namespace meta = jb_slanglsp::reflection;
namespace fs = std::filesystem;

static void require(bool condition, const char* message, slang::IBlob* diagnostics = nullptr) {
    if (!condition) {
        if (diagnostics) {
            std::cerr.write(static_cast<const char*>(diagnostics->getBufferPointer()), diagnostics->getBufferSize());
        }
        throw std::runtime_error(message);
    }
}

static void write_file(const fs::path& path, std::string_view bytes) {
    if (fs::is_regular_file(path) && meta::read_binary(path) == bytes) {
        return;
    }
    std::ofstream output(path, std::ios::binary);
    output.exceptions(std::ios::badbit | std::ios::failbit);
    output.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
    output.close();
}

static bool identifier(std::string_view text) {
    const auto start = [](char c) { return c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'); };
    return !text.empty() && start(text.front()) &&
        std::all_of(text.begin() + 1, text.end(), [&](char c) { return start(c) || (c >= '0' && c <= '9'); });
}

static std::string render_bindings(slang::TypeLayoutReflection* layout, std::string_view name, std::size_t& name_offset) {
    const auto size = layout->getSize();
    const auto alignment = layout->getAlignment();
    require(identifier(name) && size && size < SLANG_UNKNOWN_SIZE && alignment > 0, "Unsupported playground layout");

    std::ostringstream header;
    std::ostringstream checks;
    header << "#pragma once\n\n#include <cstddef>\n\nstruct alignas(" << alignment << ") ";
    name_offset = static_cast<std::size_t>(header.tellp());
    header << name << "\n{\n";
    checks << "static_assert(sizeof(" << name << ") == " << size << ");\n";
    for (unsigned i = 0; i < layout->getFieldCount(); ++i) {
        const auto field = layout->getFieldByIndex(i);
        const auto member = field->getName();
        require(member && identifier(member) && field->getType()->getScalarType() == slang::TypeReflection::Float32,
                "The playground generator supports only named float32 fields");
        header << "    float " << member << ";\n";
        checks << "static_assert(offsetof(" << name << ", " << member << ") == " << field->getOffset() << ");\n";
    }
    header << "};\n\n" << checks.str();
    return header.str();
}

static slang::SourceLocation origin(slang::ISession* session, slang::IModule* module, slang::TypeReflection* type) {
    slang::DeclReflection* match = nullptr;
    // The fixture has one top-level source module. Compare compiler identity in this session,
    // not the short name or the field layout. Do not claim origins outside this scope.
    for (auto decl : module->getModuleReflection()->getChildrenOfKind<slang::DeclReflection::Kind::Struct>()) {
        if (decl->getType() == type) {
            require(!match, "Slang reported several origins for the reflected type");
            match = decl;
        }
    }
    slang::SourceLocation location;
    require(match && SLANG_SUCCEEDED(session->getDeclSourceLocation(match, &location)) &&
        location.filePath && location.line > 0 && location.column > 0, "Slang did not report an exact declaration location");
    return location;
}

static void generate(
    const meta::Build& build, const fs::path& shader, const fs::path& header, std::string_view cpp_name,
    const std::vector<fs::path>& input_paths
) {
    const auto snapshot = meta::read_binary(shader);
    require(snapshot.find('\0') == std::string::npos, "Unexpected NUL byte in the shader");
    std::vector<std::string> snapshots;
    snapshots.reserve(input_paths.size());
    std::vector<meta::Input> inputs;
    for (const auto& path : input_paths) {
        snapshots.push_back(meta::read_binary(path));
        inputs.push_back({{meta::Root::project, path}, snapshots.back()});
    }

    ComPtr<slang::IGlobalSession> global;
    require(SLANG_SUCCEEDED(slang::createGlobalSession(global.writeRef())), "Could not start Slang");
    slang::TargetDesc target;
    target.format = SLANG_SPIRV;
    slang::SessionDesc settings;
    settings.targets = &target;
    settings.targetCount = 1;
    ComPtr<slang::ISession> session;
    require(SLANG_SUCCEEDED(global->createSession(settings, session.writeRef())), "Could not create the Slang session");

    ComPtr<slang::IBlob> diagnostics;
    const auto module = session->loadModuleFromSourceString("reflection", shader.u8string().c_str(),
        snapshot.c_str(), diagnostics.writeRef());
    require(module, "Could not load reflection.slang", diagnostics);
    // No generated mapping is published for a different source file or untracked import.
    require(module->getDependencyFileCount() == 1, "The playground shader must be self-contained");

    ComPtr<slang::IEntryPoint> entry;
    require(SLANG_SUCCEEDED(module->findEntryPointByName("reflectionMain", entry.writeRef())), "Missing reflectionMain");
    slang::IComponentType* components[] = {module, entry};
    ComPtr<slang::IComponentType> program;
    require(SLANG_SUCCEEDED(session->createCompositeComponentType(components, 2, program.writeRef())),
        "Could not compose the Slang program");
    ComPtr<slang::IComponentType> linked;
    auto status = program->link(linked.writeRef(), diagnostics.writeRef());
    require(SLANG_SUCCEEDED(status), "Could not link the Slang program", diagnostics);
    ComPtr<slang::IBlob> code;
    status = linked->getEntryPointCode(0, 0, code.writeRef(), diagnostics.writeRef());
    require(SLANG_SUCCEEDED(status), "Could not emit SPIR-V", diagnostics);

    const auto layout = linked->getLayout();
    require(layout && layout->getParameterCount() > 0, "No reflected parameters");
    const auto parameter = layout->getParameterByIndex(0);
    require(parameter->getName() && std::string_view(parameter->getName()) == "material", "The first parameter must be material");
    const auto buffer = parameter->getTypeLayout();
    require(buffer->getKind() == slang::TypeReflection::Kind::ConstantBuffer, "material must be a constant buffer");
    const auto element = buffer->getElementTypeLayout();
    require(element && element->getKind() == slang::TypeReflection::Kind::Struct, "material must contain a struct");
    const auto type = element->getType();
    const auto source = origin(session, module, type);
    require(fs::equivalent(shader, fs::u8path(source.filePath)), "The reflected type is not declared in the input shader");
    const std::string_view slang_name(type->getName());
    const auto slang_range = meta::name_at(snapshot, source.line, source.column, slang_name);

    fs::create_directories(header.parent_path());
    std::size_t cpp_offset = 0;
    const auto bindings = render_bindings(element, cpp_name, cpp_offset);
    write_file(header, bindings);
    write_file(header.parent_path() / "reflection.spv",
        {static_cast<const char*>(code->getBufferPointer()), code->getBufferSize()});
    const auto result = meta::write_reflection_meta(build, {{shader, snapshot, {{
        {slang_name, slang_range},
        {{{meta::Root::build, header}, {cpp_name, meta::ByteRange::at(cpp_offset, cpp_name)}}},
    }}, inputs}});
    if (!result) {
        throw std::runtime_error(result.error);
    }
}

static int run(const std::vector<std::string>& args) {
    if (args.size() != 10 && args.size() != 12) {
        std::cerr << "Usage: generate WORKSPACE BUILD SYSTEM TARGET CONFIG PLATFORM SHADER HEADER CPP_NAME [SLN VCXPROJ]\n";
        return 2;
    }
    try {
        const meta::Build build{fs::u8path(args[1]), fs::u8path(args[2]), args[3], args[4], args[5], args[6]};
        const auto inputs = args.size() == 12 ? std::vector{fs::u8path(args[10]), fs::u8path(args[11])} : std::vector<fs::path>{};
        generate(build, fs::u8path(args[7]), fs::u8path(args[8]), args[9], inputs);
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "Reflection playground: " << error.what() << '\n';
        return 1;
    }
}

#ifdef _WIN32
int wmain(int argc, wchar_t** argv) {
    std::vector<std::string> args;
    for (int i = 0; i < argc; ++i) {
        args.push_back(fs::path(argv[i]).u8string());
    }
    return run(args);
}
#else
int main(int argc, char** argv) {
    return run({argv, argv + argc});
}
#endif
