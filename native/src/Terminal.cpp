#include "Host.hpp"

#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <string>

#ifdef _WIN32
#include <io.h>
#define QZE_ISATTY _isatty
#define QZE_FILENO _fileno
#else
#include <unistd.h>
#define QZE_ISATTY isatty
#define QZE_FILENO fileno
#endif

namespace quartz::embed {

namespace {

bool write(std::FILE* to, const qz_slot* args, bool newline) {
    std::string_view t = text(args[0]);
    std::fwrite(t.data(), 1, t.size(), to);
    if (newline) {
        std::fputc('\n', to);
    }
    std::fflush(to);
    return true;
}

bool print(qz_call*, const qz_slot* args, qz_slot*, Host&, std::uint32_t) { return write(stdout, args, false); }
bool println(qz_call*, const qz_slot* args, qz_slot*, Host&, std::uint32_t) { return write(stdout, args, true); }
bool eprintln(qz_call*, const qz_slot* args, qz_slot*, Host&, std::uint32_t) { return write(stderr, args, true); }

bool readLine(qz_call* call, const qz_slot*, qz_slot* result, Host&, std::uint32_t) {
    std::string line;
    if (!std::getline(std::cin, line)) {
        result->p = nullptr;
        return true;
    }
    if (!line.empty() && line.back() == '\r') {
        line.pop_back();
    }
    *result = makeStr(call, line);
    return true;
}

bool interactive(qz_call*, const qz_slot*, qz_slot* result, Host&, std::uint32_t) {
    result->i = QZE_ISATTY(QZE_FILENO(stdout)) ? 1 : 0;
    return true;
}

bool width(qz_call*, const qz_slot*, qz_slot* result, Host&, std::uint32_t) {
    const char* columns = std::getenv("COLUMNS");
    long n = columns != nullptr ? std::strtol(columns, nullptr, 10) : 0;
    result->i = n > 0 ? n : 80;
    return true;
}

constexpr KitEntry kEntries[] = {
    {"org.quartzlang.embed.terminal.print", print},
    {"org.quartzlang.embed.terminal.println", println},
    {"org.quartzlang.embed.terminal.eprintln", eprintln},
    {"org.quartzlang.embed.terminal.readLine", readLine},
    {"org.quartzlang.embed.terminal.interactive", interactive},
    {"org.quartzlang.embed.terminal.width", width},
};

} // namespace

std::span<const KitEntry> terminalKit() { return kEntries; }

} // namespace quartz::embed
