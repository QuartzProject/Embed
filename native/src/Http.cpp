#include "Spawn.hpp"

#include <algorithm>
#include <cstdlib>
#include <string>
#include <vector>

namespace quartz::embed {

namespace {

constexpr const char* kSend = "org.quartzlang.http.send";

std::uint32_t fieldOffset(const Host& host, std::uint32_t type, std::uint32_t field) {
    std::uint32_t offset = 0;
    qz_type_field(host.program, type, field, &offset);
    return offset;
}

std::uint32_t fieldType(const Host& host, std::uint32_t type, std::uint32_t field) {
    std::uint32_t offset = 0;
    return qz_type_field(host.program, type, field, &offset);
}

struct Response {
    std::int64_t status = 0;
    std::vector<std::pair<std::string, std::string>> headers;
    std::string body;
};

/// Splits what `curl --include` printed: one header block per response, redirects and `100 Continue` first.
bool parse(std::string_view raw, Response& out) {
    while (raw.starts_with("HTTP/")) {
        std::size_t end = raw.find("\r\n\r\n");
        if (end == std::string_view::npos) {
            return false;
        }
        std::string_view block = raw.substr(0, end);
        raw.remove_prefix(end + 4);
        std::size_t space = block.find(' ');
        out.status = space == std::string_view::npos ? 0 : std::strtoll(block.data() + space + 1, nullptr, 10);
        out.headers.clear();
        std::size_t line = block.find("\r\n");
        while (line != std::string_view::npos) {
            block.remove_prefix(line + 2);
            line = block.find("\r\n");
            std::string_view header = block.substr(0, line);
            std::size_t colon = header.find(':');
            if (colon != std::string_view::npos) {
                std::string_view value = header.substr(colon + 1);
                while (!value.empty() && value.front() == ' ') {
                    value.remove_prefix(1);
                }
                out.headers.emplace_back(std::string(header.substr(0, colon)), std::string(value));
            }
        }
        if (out.status >= 200 && !(out.status >= 300 && out.status < 400 && raw.starts_with("HTTP/"))) {
            break;
        }
    }
    out.body.assign(raw);
    return out.status != 0;
}

/// `send(request: Request) -> Result<Response, str>` of the library, through the `curl` program.
bool send(qz_call* call, const qz_slot* args, qz_slot*, Host& host, std::uint32_t type) {
    std::uint32_t import = 0;
    qz_program_find_import(host.program, kSend, &import);
    std::uint32_t request = qz_program_import_param_type(host.program, import, 0);
    auto field = [&](std::uint32_t i) -> const qz_slot& { return args[fieldOffset(host, request, i)]; };
    std::int64_t timeout = std::max<std::int64_t>(1, (field(4).i + 999) / 1000);
    std::vector<std::string> command = {"curl", "--silent", "--show-error", "--include", "--location",
                                        "--max-time", std::to_string(timeout), "--request", std::string(text(field(1)))};
    const qz_slot& headers = field(2);
    for (std::size_t i = 0; i < qz_list_length_at(&headers); ++i) {
        qz_slot pair[2]{};
        qz_list_get_at(&headers, i, pair, 2);
        command.push_back("--header");
        command.push_back(std::string(text(pair[0])) + ": " + std::string(text(pair[1])));
    }
    SpawnOptions options;
    options.capture = true;
    if (field(3).p != nullptr) {
        options.input = text(field(3));
        command.push_back("--data-binary");
        command.push_back("@-");
    }
    command.push_back("--");
    command.push_back(std::string(text(field(0))));
    ExecResult r;
    if (auto why = spawn(command, options, r)) {
        return err(call, host, type, *why);
    }
    if (r.code == 127) {
        return err(call, host, type, "the http kit runs curl, which is not on the PATH");
    }
    Response response;
    if (r.code != 0 || !parse(r.out, response)) {
        std::string why = r.err.empty() ? "curl failed with code " + std::to_string(r.code) : r.err;
        while (!why.empty() && (why.back() == '\n' || why.back() == '\r')) {
            why.pop_back();
        }
        return err(call, host, type, why);
    }
    std::uint32_t responseType = okType(host, type);
    std::uint32_t at = 0;
    qz_type_variant_payload(host.program, type, 0, 0, &at);
    std::uint32_t slots = 0;
    qz_type_kind(host.program, type, &slots);
    qz_slot* out = qz_call_result_slots(call);
    std::fill(out, out + slots, qz_slot{});
    out[at + fieldOffset(host, responseType, 0)].i = response.status;
    qz_sandbox* sandbox = qz_call_sandbox(call);
    qz_slot list = qz_sandbox_list(sandbox, fieldType(host, responseType, 1));
    for (const auto& [name, value] : response.headers) {
        qz_slot pair[2] = {makeStr(call, name), makeStr(call, value)};
        if (!qz_list_push(sandbox, list, pair, 2)) {
            return fail(call, "out of memory building the headers");
        }
    }
    out[at + fieldOffset(host, responseType, 1)] = list;
    out[at + fieldOffset(host, responseType, 2)] = makeBytes(call, response.body);
    return true;
}

constexpr KitEntry kEntries[] = {
    {kSend, send},
};

} // namespace

std::span<const KitEntry> httpKit() { return kEntries; }

} // namespace quartz::embed
