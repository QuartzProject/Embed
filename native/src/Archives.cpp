#include "Host.hpp"

#include <algorithm>
#include <cstring>
#include <memory>
#include <span>

namespace quartz::embed {

namespace {

namespace fs = std::filesystem;
using Bytes = std::span<const std::uint8_t>;

constexpr std::size_t kMaxEntry = std::size_t{1} << 31;

std::uint32_t le(Bytes b, std::size_t at, int n) {
    std::uint32_t v = 0;
    for (int i = n - 1; i >= 0; --i) {
        v = (v << 8) | b[at + static_cast<std::size_t>(i)];
    }
    return v;
}

void put(std::string& out, std::uint32_t v, int n) {
    for (int i = 0; i < n; ++i) {
        out.push_back(static_cast<char>((v >> (8 * i)) & 0xFF));
    }
}

Bytes bytes(const std::string& s) { return {reinterpret_cast<const std::uint8_t*>(s.data()), s.size()}; }

struct QzFree {
    void operator()(void* p) const { qz_free(p); }
};

/// The error of a failed qz_inflate, or nothing with the data in `out`.
std::optional<std::string> inflate(Bytes in, qz_framing framing, std::size_t max, std::vector<std::uint8_t>& out) {
    std::size_t length = 0;
    char* error = nullptr;
    std::unique_ptr<std::uint8_t, QzFree> data(qz_inflate(in.data(), in.size(), framing, max, &length, &error));
    std::unique_ptr<char, QzFree> why(error);
    if (data == nullptr) {
        return why != nullptr ? std::string(why.get()) : std::string("cannot decompress the data");
    }
    out.assign(data.get(), data.get() + length);
    return std::nullopt;
}

bool deflate(Bytes in, std::vector<std::uint8_t>& out) {
    std::size_t length = 0;
    std::unique_ptr<std::uint8_t, QzFree> data(qz_deflate(in.data(), in.size(), 6, QZ_FRAMING_RAW, &length));
    if (data == nullptr) {
        return false;
    }
    out.assign(data.get(), data.get() + length);
    return true;
}

std::uint32_t crc32(Bytes in) { return qz_crc32(0, in.data(), in.size()); }

struct ZipEntry {
    std::string name;
    std::uint32_t method = 0;
    std::uint32_t crc = 0;
    std::size_t compressed = 0;
    std::size_t size = 0;
    std::size_t dataAt = 0;
};

/// The entries of a zip read from its central directory; the reason when it is not a zip this kit reads.
std::optional<std::string> readZip(Bytes b, std::vector<ZipEntry>& out) {
    if (b.size() < 22) {
        return "not a zip archive";
    }
    std::size_t end = b.size() - 22;
    std::size_t stop = b.size() > 65557 ? b.size() - 65557 : 0;
    while (le(b, end, 4) != 0x06054b50) {
        if (end == stop) {
            return "not a zip archive";
        }
        --end;
    }
    std::size_t count = le(b, end + 10, 2);
    std::size_t at = le(b, end + 16, 4);
    for (std::size_t i = 0; i < count; ++i) {
        if (at + 46 > b.size() || le(b, at, 4) != 0x02014b50) {
            return "a damaged zip directory";
        }
        ZipEntry e;
        e.method = le(b, at + 10, 2);
        e.crc = le(b, at + 16, 4);
        e.compressed = le(b, at + 20, 4);
        e.size = le(b, at + 24, 4);
        std::size_t nameLength = le(b, at + 28, 2);
        std::size_t skip = nameLength + le(b, at + 30, 2) + le(b, at + 32, 2);
        std::size_t local = le(b, at + 42, 4);
        if (e.compressed == 0xFFFFFFFF || e.size == 0xFFFFFFFF || local == 0xFFFFFFFF) {
            return "zip64 archives are not supported";
        }
        if (at + 46 + nameLength > b.size() || local + 30 > b.size()) {
            return "a damaged zip directory";
        }
        e.name.assign(reinterpret_cast<const char*>(b.data() + at + 46), nameLength);
        e.dataAt = local + 30 + le(b, local + 26, 2) + le(b, local + 28, 2);
        if (e.dataAt + e.compressed > b.size()) {
            return "a damaged zip entry " + e.name;
        }
        out.push_back(std::move(e));
        at += 46 + skip;
    }
    return std::nullopt;
}

/// `target/name`, refused when the name would land outside the target or the granted paths.
std::optional<std::string> inside(const Host& host, const fs::path& target, const std::string& name, fs::path& out) {
    fs::path p = (target / fs::path(name)).lexically_normal();
    auto [t, q] = std::mismatch(target.begin(), target.end(), p.begin(), p.end());
    if (name.empty() || name.starts_with('/') || name.find('\\') != std::string::npos || t != target.end()) {
        return "archive entry '" + name + "' would land outside the target";
    }
    return resolvePath(host, p.generic_string(), out);
}

#define QZE_PATH(name, index)                                                                                          \
    fs::path name;                                                                                                     \
    if (auto why = resolvePath(host, text(args[index]), name)) {                                                       \
        return err(call, host, type, *why);                                                                            \
    }

std::optional<std::string> load(const qz_slot* args, const Host& host, std::string& data) {
    fs::path p;
    if (auto why = resolvePath(host, text(args[0]), p)) {
        return why;
    }
    if (!readFile(p, data)) {
        return std::string(text(args[0])) + ": no such file";
    }
    return std::nullopt;
}

bool entries(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    std::string data;
    std::vector<ZipEntry> zip;
    if (auto why = load(args, host, data)) {
        return err(call, host, type, *why);
    }
    if (auto why = readZip(bytes(data), zip)) {
        return err(call, host, type, *why);
    }
    std::vector<std::string> names;
    for (const ZipEntry& e : zip) {
        if (!e.name.ends_with('/')) {
            names.push_back(e.name);
        }
    }
    qz_slot list{};
    return makeStrList(call, okType(host, type), names, &list) && ok(call, host, type, list);
}

bool unzip(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    std::string data;
    std::vector<ZipEntry> zip;
    if (auto why = load(args, host, data)) {
        return err(call, host, type, *why);
    }
    QZE_PATH(target, 1);
    if (auto why = readZip(bytes(data), zip)) {
        return err(call, host, type, *why);
    }
    std::int64_t written = 0;
    for (const ZipEntry& e : zip) {
        fs::path out;
        if (auto why = inside(host, target, e.name, out)) {
            return err(call, host, type, *why);
        }
        std::error_code ec;
        if (e.name.ends_with('/')) {
            fs::create_directories(out, ec);
            continue;
        }
        Bytes raw = bytes(data).subspan(e.dataAt, e.compressed);
        std::vector<std::uint8_t> content;
        if (e.method == 0) {
            content.assign(raw.begin(), raw.end());
        } else if (e.method != 8) {
            return err(call, host, type, e.name + ": an unsupported compression method");
        } else if (auto why = inflate(raw, QZ_FRAMING_RAW, std::min(e.size, kMaxEntry), content)) {
            return err(call, host, type, e.name + ": " + *why);
        }
        if (content.size() != e.size || crc32(content) != e.crc) {
            return err(call, host, type, e.name + ": the data does not match its checksum");
        }
        if (!writeFile(out, {reinterpret_cast<const char*>(content.data()), content.size()}, false)) {
            return err(call, host, type, "cannot write " + out.generic_string());
        }
        ++written;
    }
    return ok(call, host, type, qz_slot{.i = written});
}

bool zip(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    QZE_PATH(dir, 0);
    QZE_PATH(archive, 1);
    std::error_code ec;
    std::vector<fs::path> files;
    for (const auto& entry : fs::recursive_directory_iterator(dir, ec)) {
        if (entry.is_regular_file(ec) && entry.path() != archive) {
            files.push_back(entry.path());
        }
    }
    if (ec) {
        return err(call, host, type, ec.message());
    }
    std::sort(files.begin(), files.end());
    std::string out;
    std::string central;
    for (const fs::path& file : files) {
        std::string content;
        if (!readFile(file, content)) {
            return err(call, host, type, "cannot read " + file.generic_string());
        }
        std::string name = file.lexically_relative(dir).generic_string();
        std::vector<std::uint8_t> packed;
        if (!deflate(bytes(content), packed)) {
            return err(call, host, type, "out of memory compressing " + name);
        }
        std::uint32_t crc = crc32(bytes(content));
        std::uint32_t offset = static_cast<std::uint32_t>(out.size());
        auto header = [&](std::string& to, bool local) {
            put(to, local ? 0x04034b50 : 0x02014b50, 4);
            if (!local) {
                put(to, 20, 2);
            }
            put(to, 20, 2);
            put(to, 0x0800, 2);
            put(to, 8, 2);
            put(to, 0, 2);
            put(to, 0x21, 2);
            put(to, crc, 4);
            put(to, static_cast<std::uint32_t>(packed.size()), 4);
            put(to, static_cast<std::uint32_t>(content.size()), 4);
            put(to, static_cast<std::uint32_t>(name.size()), 2);
            put(to, 0, 2);
            if (!local) {
                put(to, 0, 2);
                put(to, 0, 2);
                put(to, 0, 2);
                put(to, 0, 4);
                put(to, offset, 4);
            }
            to += name;
        };
        header(out, true);
        out.append(reinterpret_cast<const char*>(packed.data()), packed.size());
        header(central, false);
    }
    std::uint32_t at = static_cast<std::uint32_t>(out.size());
    out += central;
    put(out, 0x06054b50, 4);
    put(out, 0, 4);
    put(out, static_cast<std::uint32_t>(files.size()), 2);
    put(out, static_cast<std::uint32_t>(files.size()), 2);
    put(out, static_cast<std::uint32_t>(central.size()), 4);
    put(out, at, 4);
    put(out, 0, 2);
    if (!writeFile(archive, out, false)) {
        return err(call, host, type, "cannot write " + std::string(text(args[1])));
    }
    return ok(call, host, type, qz_slot{.i = static_cast<std::int64_t>(files.size())});
}

/// The tar inside a .tar.gz, or the bytes unchanged.
std::optional<std::string> gunzip(std::string& data) {
    Bytes b = bytes(data);
    if (b.size() < 18 || b[0] != 0x1f || b[1] != 0x8b) {
        return std::nullopt;
    }
    std::vector<std::uint8_t> out;
    if (auto why = inflate(b, QZ_FRAMING_GZIP, kMaxEntry, out)) {
        return "gzip: " + *why;
    }
    data.assign(reinterpret_cast<const char*>(out.data()), out.size());
    return std::nullopt;
}

std::uint64_t octal(Bytes b, std::size_t at, std::size_t n) {
    std::uint64_t v = 0;
    for (std::size_t i = at; i < at + n && b[i] >= '0' && b[i] <= '7'; ++i) {
        v = v * 8 + (b[i] - '0');
    }
    return v;
}

std::string cString(Bytes b, std::size_t at, std::size_t n) {
    std::size_t end = at;
    while (end < at + n && b[end] != 0) {
        ++end;
    }
    return {reinterpret_cast<const char*>(b.data() + at), end - at};
}

bool untar(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    std::string data;
    if (auto why = load(args, host, data)) {
        return err(call, host, type, *why);
    }
    QZE_PATH(target, 1);
    if (auto why = gunzip(data)) {
        return err(call, host, type, *why);
    }
    Bytes b = bytes(data);
    std::size_t at = 0;
    std::int64_t written = 0;
    std::string longName;
    while (at + 512 <= b.size() && b[at] != 0) {
        std::uint64_t size = octal(b, at + 124, 12);
        char kind = static_cast<char>(b[at + 156]);
        std::string name = longName.empty() ? cString(b, at, 100) : longName;
        std::string prefix = cString(b, at + 345, 155);
        if (longName.empty() && !prefix.empty()) {
            name = prefix + "/" + name;
        }
        longName.clear();
        std::size_t body = at + 512;
        if (body + size > b.size()) {
            return err(call, host, type, "a truncated tar entry " + name);
        }
        at = body + (size + 511) / 512 * 512;
        if (kind == 'L') {
            longName = cString(b, body, size);
            continue;
        }
        if (kind != '0' && kind != '\0' && kind != '5') {
            continue;
        }
        fs::path out;
        if (auto why = inside(host, target, name, out)) {
            return err(call, host, type, *why);
        }
        std::error_code ec;
        if (kind == '5') {
            fs::create_directories(out, ec);
            continue;
        }
        if (!writeFile(out, {reinterpret_cast<const char*>(b.data() + body), size}, false)) {
            return err(call, host, type, "cannot write " + out.generic_string());
        }
        ++written;
    }
    return ok(call, host, type, qz_slot{.i = written});
}

constexpr KitEntry kEntries[] = {
    {"org.quartzlang.embed.archives.entries", entries},
    {"org.quartzlang.embed.archives.unzip", unzip},
    {"org.quartzlang.embed.archives.untar", untar},
    {"org.quartzlang.embed.archives.zip", zip},
};

} // namespace

std::span<const KitEntry> archivesKit() { return kEntries; }

} // namespace quartz::embed
