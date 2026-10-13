#pragma once

#include <cstdint>
#include <filesystem>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace quartz::embed {

/// What the executable grants the embedded module; a kit it was not built with is never imported.
struct Grants {
    std::filesystem::path root;                // where relative paths of the fs and archives kits start
    std::vector<std::filesystem::path> extra;  // more files or directories the fs and archives kits may touch
    std::vector<std::string> args;             // what process.args() returns
    std::size_t memoryBytes = std::size_t{1} << 30;
    std::int64_t fuelPerCall = INT64_MAX / 4;
};

/// The paths a command line names: every argument that does not start with '-', made absolute.
std::vector<std::filesystem::path> argumentPaths(std::span<const std::string> args);

/// Loads a validated .qzb, binds the kits it imports and runs `entry`; returns the process exit code.
int run(std::span<const std::uint8_t> module, const Grants& grants, std::string_view entry = "main");

} // namespace quartz::embed
