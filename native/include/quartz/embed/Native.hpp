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
    std::filesystem::path root;     // the fs kit's directory
    std::vector<std::string> args;  // what process.args() returns
    std::size_t memoryBytes = std::size_t{1} << 30;
    std::int64_t fuelPerCall = INT64_MAX / 4;
};

/// Loads a validated .qzb, binds the kits it imports and runs `entry`; returns the process exit code.
int run(std::span<const std::uint8_t> module, const Grants& grants, std::string_view entry = "main");

} // namespace quartz::embed
