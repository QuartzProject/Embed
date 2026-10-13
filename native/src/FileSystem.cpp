#include "Host.hpp"

#include <algorithm>
#include <chrono>

namespace quartz::embed {

namespace {

namespace fs = std::filesystem;

/// Resolves argument `index` into `name`, or answers the call with the error.
#define QZE_PATH(name, index)                                                                                          \
    fs::path name;                                                                                                     \
    if (auto why = resolvePath(host, text(args[index]), name)) {                                                       \
        return err(call, host, type, *why);                                                                            \
    }                                                                                                                  \
    std::error_code ec

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

bool test(const qz_slot* args, qz_slot* result, Host& host, bool (*check)(const fs::path&, std::error_code&)) {
    fs::path p;
    std::error_code ec;
    result->i = !resolvePath(host, text(args[0]), p) && check(p, ec) ? 1 : 0;
    return true;
}

bool exists(qz_call*, const qz_slot* args, qz_slot* result, Host& host, std::uint32_t) {
    return test(args, result, host, [](const fs::path& p, std::error_code& ec) {
        return fs::exists(fs::symlink_status(p, ec));
    });
}

bool isFile(qz_call*, const qz_slot* args, qz_slot* result, Host& host, std::uint32_t) {
    return test(args, result, host, [](const fs::path& p, std::error_code& ec) { return fs::is_regular_file(p, ec); });
}

bool isDirectory(qz_call*, const qz_slot* args, qz_slot* result, Host& host, std::uint32_t) {
    return test(args, result, host, [](const fs::path& p, std::error_code& ec) { return fs::is_directory(p, ec); });
}

bool read(qz_call* call, const qz_slot* args, Host& host, std::uint32_t type, bool utf8) {
    QZE_PATH(p, 0);
    std::string content;
    if (!fs::is_regular_file(p, ec) || !readFile(p, content)) {
        return err(call, host, type, std::string(text(args[0])) + ": no such file");
    }
    if (utf8 && !validUtf8(content)) {
        return err(call, host, type, std::string(text(args[0])) + " is not UTF-8 text");
    }
    return ok(call, host, type, utf8 ? makeStr(call, content) : makeBytes(call, content));
}

bool readText(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    return read(call, args, host, type, true);
}

bool readBytes(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    return read(call, args, host, type, false);
}

bool write(qz_call* call, const qz_slot* args, Host& host, std::uint32_t type, bool append) {
    QZE_PATH(p, 0);
    std::string_view data = text(args[1]);
    if (!writeFile(p, data, append)) {
        return err(call, host, type, "cannot write " + std::string(text(args[0])));
    }
    return ok(call, host, type, qz_slot{.i = static_cast<std::int64_t>(data.size())});
}

bool writeText(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    return write(call, args, host, type, false);
}

bool appendText(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    return write(call, args, host, type, true);
}

bool names(qz_call* call, const qz_slot* args, Host& host, std::uint32_t type, bool recursive) {
    QZE_PATH(p, 0);
    if (!fs::is_directory(p, ec)) {
        return err(call, host, type, std::string(text(args[0])) + " is not a directory");
    }
    std::vector<std::string> out;
    if (recursive) {
        for (const auto& entry : fs::recursive_directory_iterator(p, ec)) {
            if (entry.is_regular_file(ec)) {
                out.push_back(entry.path().lexically_relative(p).generic_string());
            }
        }
    } else {
        for (const auto& entry : fs::directory_iterator(p, ec)) {
            out.push_back(entry.path().filename().generic_string());
        }
    }
    std::sort(out.begin(), out.end());
    qz_slot list{};
    return makeStrList(call, okType(host, type), out, &list) && ok(call, host, type, list);
}

bool list(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    return names(call, args, host, type, false);
}

bool walk(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    return names(call, args, host, type, true);
}

bool createDirectories(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    QZE_PATH(p, 0);
    bool created = fs::create_directories(p, ec);
    return ec ? err(call, host, type, ec.message()) : ok(call, host, type, qz_slot{.i = created ? 1 : 0});
}

bool remove(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    QZE_PATH(p, 0);
    if (std::find(host.roots.begin(), host.roots.end(), p) != host.roots.end()) {
        return err(call, host, type, "a granted path itself cannot be deleted");
    }
    bool removed = fs::remove_all(p, ec) > 0;
    return ec ? err(call, host, type, ec.message()) : ok(call, host, type, qz_slot{.i = removed ? 1 : 0});
}

bool copyOrMove(qz_call* call, const qz_slot* args, Host& host, std::uint32_t type, bool move) {
    QZE_PATH(from, 0);
    fs::path to;
    if (auto why = resolvePath(host, text(args[1]), to)) {
        return err(call, host, type, *why);
    }
    fs::create_directories(to.parent_path(), ec);
    if (move) {
        fs::rename(from, to, ec);
    } else {
        fs::copy(from, to, fs::copy_options::overwrite_existing | fs::copy_options::recursive, ec);
    }
    return ec ? err(call, host, type, ec.message()) : ok(call, host, type, qz_slot{.i = 1});
}

bool copy(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    return copyOrMove(call, args, host, type, false);
}

bool move(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    return copyOrMove(call, args, host, type, true);
}

bool size(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    QZE_PATH(p, 0);
    auto n = fs::file_size(p, ec);
    return ec ? err(call, host, type, ec.message()) : ok(call, host, type, qz_slot{.i = static_cast<std::int64_t>(n)});
}

bool modified(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    QZE_PATH(p, 0);
    auto time = fs::last_write_time(p, ec);
    if (ec) {
        return err(call, host, type, ec.message());
    }
    auto system = std::chrono::clock_cast<std::chrono::system_clock>(time);
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(system.time_since_epoch()).count();
    return ok(call, host, type, qz_slot{.i = static_cast<std::int64_t>(ms)});
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
