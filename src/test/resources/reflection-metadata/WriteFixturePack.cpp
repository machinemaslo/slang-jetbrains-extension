#include <cstdint>
#include <fstream>
#include <iterator>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>

// Independent encoder for the fixture's one shader, one binding and one destination.
static void number(std::ostream& out, std::uint64_t value, unsigned width) {
    for (unsigned i = 0; i < width; ++i) {
        out.put(static_cast<char>(value & 0xff));
        value >>= 8;
    }
}

static void text(std::ostream& out, std::string_view value) {
    number(out, value.size(), 4);
    out << value;
}

static void digest(std::ostream& out, const std::string& hex) {
    if (hex.size() != 64) {
        throw std::runtime_error("Expected a fixture SHA-256 digest");
    }
    for (std::size_t i = 0; i < hex.size(); i += 2) {
        number(out, std::stoul(hex.substr(i, 2), nullptr, 16), 1);
    }
}

static std::size_t fixture_offset(const char* path, std::string_view name) {
    std::ifstream input(path, std::ios::binary);
    input.exceptions(std::ios::badbit | std::ios::failbit);
    const std::string bytes{std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
    const auto offset = bytes.find(name);
    if (offset == std::string::npos) {
        throw std::runtime_error("Fixture declaration not found");
    }
    return offset;
}

int main(int argc, char** argv) {
    if (argc != 14) {
        return 2;
    }
    std::ostringstream preamble;
    preamble.write("JBSLRPK\0", 8);
    number(preamble, 1, 4);
    number(preamble, 1, 4);
    text(preamble, "cmake");
    text(preamble, "host");
    text(preamble, argv[2]); // Configuration
    text(preamble, "");

    std::ostringstream record;
    text(record, argv[4]);   // Source path
    digest(record, argv[5]); // Source hash
    number(record, 1, 4);
    number(record, 0, 1);   // Project root
    text(record, argv[12]);  // Additional generator input
    digest(record, argv[13]);
    number(record, 1, 4);
    number(record, fixture_offset(argv[6], argv[7]), 8);
    text(record, argv[7]);   // Slang declaration
    number(record, 1, 4);
    number(record, 1, 1);   // Build root
    text(record, argv[8]);   // Destination path
    digest(record, argv[9]); // Destination hash
    number(record, fixture_offset(argv[10], argv[11]), 8);

    text(record, argv[11]); // C++ declaration

    const auto prefix = preamble.str();
    const auto body = record.str();
    std::ofstream output(argv[1], std::ios::binary);
    output.exceptions(std::ios::badbit | std::ios::failbit);
    output << prefix;
    digest(output, argv[3]); // Shader key
    number(output, prefix.size() + 48, 8);
    number(output, body.size(), 8);
    output << body;
    output.close();
}
