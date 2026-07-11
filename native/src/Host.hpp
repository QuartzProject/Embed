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
    std::filesystem::path realRoot;
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

// Helpers shared by the kits.
std::string_view text(const qz_slot& slot);
bool fail(qz_call* call, const std::string& message);
qz_slot makeStr(qz_call* call, std::string_view value);
/// A `[str]` result in the sandbox, built from `items`.
bool makeStrList(qz_call* call, std::uint32_t type, const std::vector<std::string>& items, qz_slot* out);
std::vector<std::string> strList(const qz_slot& list);

} // namespace quartz::embed
