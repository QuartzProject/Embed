#pragma once

#include "Host.hpp"

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace quartz::embed {

struct SpawnOptions {
    std::string dir;              // empty keeps the working directory
    bool capture = false;         // collect standard output and error instead of sharing the terminal
    std::optional<std::string_view> input; // written to standard input, which is then closed
};

/// Runs `command` to the end; the reason when it could not start. 127 is the code of a program that was not found.
std::optional<std::string> spawn(const std::vector<std::string>& command, const SpawnOptions& options,
                                 ExecResult& out);

} // namespace quartz::embed
