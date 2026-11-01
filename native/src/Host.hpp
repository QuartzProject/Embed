#pragma once

#include "quartz/embed/Native.hpp"

#include <quartz/quartz.h>

#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace quartz::embed {

/// The captured output of `process.exec`, lent to the module as an `Exec` handle.
struct ExecResult {
    std::int64_t code = 0;
    std::string out;
    std::string err;
};

struct Host {
    const Grants* grants = nullptr;
    qz_program* program = nullptr;
    std::vector<std::filesystem::path> roots;     // absolute and normal; the first is where relative paths start
    std::vector<std::filesystem::path> realRoots; // the same with links resolved
    std::optional<int> exitCode;
    std::vector<ExecResult> execs;
};

/// One kit function: reads its arguments, writes its result, returns false after qz_call_fail.
using KitFn = bool (*)(qz_call* call, const qz_slot* args, qz_slot* result, Host& host, std::uint32_t resultType);

struct KitEntry {
    std::string_view name;
    KitFn fn;
};

std::span<const KitEntry> terminalKit();
std::span<const KitEntry> fsKit();
std::span<const KitEntry> processKit();
std::span<const KitEntry> httpKit();
std::span<const KitEntry> archivesKit();

// Helpers shared by the kits.
std::string_view text(const qz_slot& slot);
bool fail(qz_call* call, const std::string& message);
qz_slot makeStr(qz_call* call, std::string_view value);
/// A `str` from text the host did not write (program output, headers): ill-formed UTF-8 becomes U+FFFD.
qz_slot makeStrLossy(qz_call* call, std::string_view value);
qz_slot makeBytes(qz_call* call, std::string_view value);
/// A `[str]` in the sandbox, built from `items`, of list type `type`.
bool makeStrList(qz_call* call, std::uint32_t type, const std::vector<std::string>& items, qz_slot* out);
std::vector<std::string> strList(const qz_slot& list);

/// The type of the value `Ok` carries in the Result type `type`.
std::uint32_t okType(const Host& host, std::uint32_t type);
/// Writes `Ok(value)` or `Err(message)` of the Result type `type` into the result slots.
bool ok(qz_call* call, const Host& host, std::uint32_t type, qz_slot value);
bool err(qz_call* call, const Host& host, std::uint32_t type, const std::string& message);

/// `path` inside the granted paths, or the reason it is not; links are resolved before the check.
std::optional<std::string> resolvePath(const Host& host, std::string_view path, std::filesystem::path& out);
bool readFile(const std::filesystem::path& p, std::string& out);
bool writeFile(const std::filesystem::path& p, std::string_view data, bool append);

} // namespace quartz::embed
