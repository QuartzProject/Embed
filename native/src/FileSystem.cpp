#include "Host.hpp"

#include <algorithm>
#include <chrono>
#include <fstream>
#include <iterator>
#include <sstream>

namespace quartz::embed {

namespace {

namespace fs = std::filesystem;

bool within(const fs::path& path, const fs::path& root) {
    auto [r, p] = std::mismatch(root.begin(), root.end(), path.begin(), path.end());
    return r == root.end();
}

/// The path inside the root, or an error naming why it leaves it; links are resolved before the check.
bool resolve(qz_call* call, Host& host, const qz_slot& arg, fs::path& out) {
    std::string rel(text(arg));
    if (rel.find('\0') != std::string::npos || rel.starts_with('/') || rel.starts_with('\\') ||
        rel.find(':') != std::string::npos) {
        return fail(call, "'" + rel + "' is not a relative path");
    }
    std::replace(rel.begin(), rel.end(), '\\', '/');
    fs::path p = (host.realRoot / fs::path(rel)).lexically_normal();
    if (!within(p, host.realRoot)) {
        return fail(call, "'" + rel + "' leaves the directory");
    }
    std::error_code ec;
    fs::path existing = p;
    while (!existing.empty() && !fs::exists(fs::symlink_status(existing, ec))) {
        existing = existing.parent_path();
    }
    if (!existing.empty() && !within(fs::canonical(existing, ec), host.realRoot)) {
        return fail(call, "'" + rel + "' leaves the directory through a link");
    }
    if (ec) {
        return fail(call, "'" + rel + "': " + ec.message());
    }
    out = p;
    return true;
}

bool readFile(const fs::path& p, std::string& out) {
    std::ifstream in(p, std::ios::binary);
    if (!in) {
        return false;
    }
    out.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    return true;
}

bool writeFile(qz_call* call, Host& host, const qz_slot* args, bool append) {
    fs::path p;
    if (!resolve(call, host, args[0], p)) {
        return false;
    }
    std::error_code ec;
    fs::create_directories(p.parent_path(), ec);
    std::ofstream out(p, std::ios::binary | (append ? std::ios::app : std::ios::trunc));
    std::string_view data = text(args[1]);
    if (!out.write(data.data(), static_cast<std::streamsize>(data.size()))) {
        return fail(call, "cannot write " + std::string(text(args[0])));
    }
    return true;
}

#define QZE_PATH(index)                                                                                                \
    fs::path p;                                                                                                        \
    if (!resolve(call, host, args[index], p)) {                                                                        \
        return false;                                                                                                  \
    }                                                                                                                  \
    std::error_code ec

bool exists(qz_call* call, const qz_slot* args, qz_slot* result, Host& host, std::uint32_t) {
    QZE_PATH(0);
    result->i = fs::exists(fs::symlink_status(p, ec)) ? 1 : 0;
    return true;
}

bool isFile(qz_call* call, const qz_slot* args, qz_slot* result, Host& host, std::uint32_t) {
    QZE_PATH(0);
    result->i = fs::is_regular_file(p, ec) ? 1 : 0;
    return true;
}

bool isDirectory(qz_call* call, const qz_slot* args, qz_slot* result, Host& host, std::uint32_t) {
    QZE_PATH(0);
    result->i = fs::is_directory(p, ec) ? 1 : 0;
    return true;
}

bool validUtf8(std::string_view s) {
    std::size_t i = 0;
    while (i < s.size()) {
        auto c = static_cast<unsigned char>(s[i]);
        std::size_t n = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
        if (n == 0 || i + n > s.size()) {
            return false;
        }
        std::uint32_t cp = n == 1 ? c : c & (0x7F >> n);
        for (std::size_t k = 1; k < n; ++k) {
            auto d = static_cast<unsigned char>(s[i + k]);
            if ((d >> 6) != 0x2) {
                return false;
            }
            cp = (cp << 6) | (d & 0x3F);
        }
        constexpr std::uint32_t kMin[] = {0, 0, 0x80, 0x800, 0x10000};
        if (cp < kMin[n] || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            return false;
        }
        i += n;
    }
    return true;
}

bool read(qz_call* call, const qz_slot* args, qz_slot* result, Host& host, bool utf8) {
    QZE_PATH(0);
    std::string content;
    if (!fs::is_regular_file(p, ec) || !readFile(p, content)) {
        result->p = nullptr;
        return true;
    }
    if (utf8 && !validUtf8(content)) {
        return fail(call, std::string(text(args[0])) + " is not UTF-8 text");
    }
    *result = makeStr(call, content);
    return true;
}

bool readText(qz_call* call, const qz_slot* args, qz_slot* result, Host& host, std::uint32_t) {
    return read(call, args, result, host, true);
}

bool readBytes(qz_call* call, const qz_slot* args, qz_slot* result, Host& host, std::uint32_t) {
    return read(call, args, result, host, false);
}

bool writeText(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t) {
    return writeFile(call, host, args, false);
}

bool appendText(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t) {
    return writeFile(call, host, args, true);
}

bool list(qz_call* call, const qz_slot* args, qz_slot* result, Host& host, std::uint32_t type) {
    QZE_PATH(0);
    std::vector<std::string> names;
    if (fs::is_directory(p, ec)) {
        for (const auto& entry : fs::directory_iterator(p, ec)) {
            names.push_back(entry.path().filename().generic_string());
        }
    }
    std::sort(names.begin(), names.end());
    return makeStrList(call, type, names, result);
}

bool walk(qz_call* call, const qz_slot* args, qz_slot* result, Host& host, std::uint32_t type) {
    QZE_PATH(0);
    std::vector<std::string> files;
    if (fs::is_directory(p, ec)) {
        for (const auto& entry : fs::recursive_directory_iterator(p, ec)) {
            if (entry.is_regular_file(ec)) {
                files.push_back(entry.path().lexically_relative(p).generic_string());
            }
        }
    }
    std::sort(files.begin(), files.end());
    return makeStrList(call, type, files, result);
}

bool createDirectories(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t) {
    QZE_PATH(0);
    fs::create_directories(p, ec);
    return ec ? fail(call, ec.message()) : true;
}

bool remove(qz_call* call, const qz_slot* args, qz_slot* result, Host& host, std::uint32_t) {
    QZE_PATH(0);
    if (p == host.realRoot) {
        return fail(call, "the root itself cannot be deleted");
    }
    result->i = fs::remove_all(p, ec) > 0 ? 1 : 0;
    return ec ? fail(call, ec.message()) : true;
}

bool copyOrMove(qz_call* call, const qz_slot* args, Host& host, bool move) {
    fs::path from;
    fs::path to;
    if (!resolve(call, host, args[0], from) || !resolve(call, host, args[1], to)) {
        return false;
    }
    std::error_code ec;
    fs::create_directories(to.parent_path(), ec);
    if (move) {
        fs::rename(from, to, ec);
    } else {
        fs::copy(from, to, fs::copy_options::overwrite_existing | fs::copy_options::recursive, ec);
    }
    return ec ? fail(call, ec.message()) : true;
}

bool copy(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t) {
    return copyOrMove(call, args, host, false);
}

bool move(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t) {
    return copyOrMove(call, args, host, true);
}

bool size(qz_call* call, const qz_slot* args, qz_slot* result, Host& host, std::uint32_t) {
    QZE_PATH(0);
    auto n = fs::file_size(p, ec);
    result->i = static_cast<std::int64_t>(n);
    return ec ? fail(call, ec.message()) : true;
}

bool modified(qz_call* call, const qz_slot* args, qz_slot* result, Host& host, std::uint32_t) {
    QZE_PATH(0);
    auto time = fs::last_write_time(p, ec);
    if (ec) {
        return fail(call, ec.message());
    }
    auto system = std::chrono::clock_cast<std::chrono::system_clock>(time);
    result->i = std::chrono::duration_cast<std::chrono::milliseconds>(system.time_since_epoch()).count();
    return true;
}

constexpr KitEntry kEntries[] = {
    {"org.quartzlang.embed.fs.exists", exists},
    {"org.quartzlang.embed.fs.isFile", isFile},
    {"org.quartzlang.embed.fs.isDirectory", isDirectory},
    {"org.quartzlang.embed.fs.readText", readText},
    {"org.quartzlang.embed.fs.readBytes", readBytes},
    {"org.quartzlang.embed.fs.writeText", writeText},
    {"org.quartzlang.embed.fs.writeBytes", writeText},
    {"org.quartzlang.embed.fs.appendText", appendText},
    {"org.quartzlang.embed.fs.list", list},
    {"org.quartzlang.embed.fs.walk", walk},
    {"org.quartzlang.embed.fs.createDirectories", createDirectories},
    {"org.quartzlang.embed.fs.delete", remove},
    {"org.quartzlang.embed.fs.copy", copy},
    {"org.quartzlang.embed.fs.move", move},
    {"org.quartzlang.embed.fs.size", size},
    {"org.quartzlang.embed.fs.modified", modified},
};

} // namespace

std::span<const KitEntry> fsKit() { return kEntries; }

} // namespace quartz::embed
