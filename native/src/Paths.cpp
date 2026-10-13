#include "Host.hpp"

#include <algorithm>
#include <fstream>
#include <iterator>

namespace quartz::embed {

namespace {

namespace fs = std::filesystem;

bool within(const fs::path& path, const fs::path& root) {
    auto [r, p] = std::mismatch(root.begin(), root.end(), path.begin(), path.end());
    return r == root.end();
}

/// The real path of `p`: links resolved up to its deepest existing ancestor, the rest appended.
fs::path real(const fs::path& p) {
    std::error_code ec;
    fs::path existing = p;
    while (!existing.empty() && existing != existing.root_path() && !fs::exists(fs::symlink_status(existing, ec))) {
        existing = existing.parent_path();
    }
    fs::path resolved = fs::canonical(existing, ec);
    return ec ? p : (resolved / p.lexically_relative(existing)).lexically_normal();
}

} // namespace

std::optional<std::string> resolvePath(const Host& host, std::string_view text, fs::path& out) {
    std::string rel(text);
    if (rel.find('\0') != std::string::npos || host.roots.empty()) {
        return "'" + rel + "' is not a path";
    }
    std::replace(rel.begin(), rel.end(), '\\', '/');
    fs::path given(rel);
    if (!given.is_absolute() && (rel.starts_with('/') || rel.find(':') != std::string::npos)) {
        return "'" + rel + "' is not a relative path";
    }
    fs::path p = (given.is_absolute() ? given : host.roots[0] / given).lexically_normal();
    for (std::size_t i = 0; i < host.roots.size(); ++i) {
        if (!within(p, host.roots[i])) {
            continue;
        }
        if (!within(real(p), host.realRoots[i])) {
            return "'" + rel + "' leaves the granted paths through a link";
        }
        out = p;
        return std::nullopt;
    }
    return "'" + rel + "' is outside the granted paths";
}

bool readFile(const fs::path& p, std::string& out) {
    std::ifstream in(p, std::ios::binary);
    if (!in) {
        return false;
    }
    out.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
    return true;
}

bool writeFile(const fs::path& p, std::string_view data, bool append) {
    std::error_code ec;
    if (p.has_parent_path()) {
        fs::create_directories(p.parent_path(), ec);
    }
    std::ofstream out(p, std::ios::binary | (append ? std::ios::app : std::ios::trunc));
    return static_cast<bool>(out.write(data.data(), static_cast<std::streamsize>(data.size())));
}

} // namespace quartz::embed
