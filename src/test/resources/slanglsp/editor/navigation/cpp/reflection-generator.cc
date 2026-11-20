#include <slang.h>
#include <slang-com-ptr.h>

#include <fstream>
#include <iostream>
#include <iterator>
#include <string>

int main(int argc, char** argv)
{
    if (argc != 3)
    {
        return 1;
    }
    std::ifstream input(argv[1]);
    std::string source{std::istreambuf_iterator<char>{input}, {}};
    Slang::ComPtr<slang::IGlobalSession> global;
    Slang::ComPtr<slang::ISession> session;
    slang::TargetDesc target = {};
    target.format = SLANG_SPIRV;
    slang::SessionDesc description = {};
    description.targets = &target;
    description.targetCount = 1;
    if (SLANG_FAILED(slang::createGlobalSession(global.writeRef())) ||
        SLANG_FAILED(global->createSession(description, session.writeRef())))
    {
        return 2;
    }
    slang::IModule* module = session->loadModuleFromSourceString("reflect", argv[1], source.c_str());
    if (module == nullptr)
    {
        return 3;
    }
    Slang::ComPtr<slang::IBlob> diagnostics;
    slang::ProgramLayout* layout = module->getLayout(0, diagnostics.writeRef());
    slang::TypeReflection* type = layout == nullptr ? nullptr : layout->findTypeByName("LightingParams");
    if (type == nullptr)
    {
        if (diagnostics)
        {
            std::cerr << static_cast<const char*>(diagnostics->getBufferPointer());
        }
        return 4;
    }
    size_t arrayLength = 0;
    for (unsigned i = 0; i < type->getFieldCount(); ++i)
    {
        auto field = type->getFieldByIndex(i)->getType();
        if (field->getKind() == slang::TypeReflection::Kind::Array)
        {
            arrayLength = field->getElementCount();
        }
    }
    std::ofstream output(argv[2]);
    output << "struct alignas(16) " << (arrayLength == 0 ? "T42" : "ScratchType") << " {\n"
           << "    float a[3] = {};\n"
           << "    float b = {};\n";
    if (arrayLength != 0)
    {
        output << "    unsigned translatorField[" << arrayLength << "] = {};\n";
    }
    output << "};\n";
    return output.good() ? 0 : 5;
}
