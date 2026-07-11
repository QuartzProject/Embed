#include "Host.hpp"

#include <cstdio>
#include <memory>

namespace quartz::embed {

namespace {

struct Binding {
    Host* host;
    KitFn fn;
    std::uint32_t resultType;
};

bool trampoline(qz_call* call, const qz_slot* args, qz_slot* result, void* user) {
    auto* b = static_cast<Binding*>(user);
    return b->fn(call, args, result, *b->host, b->resultType);
}

const KitEntry* find(std::string_view name) {
    for (auto kit : {terminalKit(), fsKit(), processKit()}) {
        for (const KitEntry& e : kit) {
            if (e.name == name) {
                return &e;
            }
        }
    }
    return nullptr;
}

struct Owned {
    qz_program* program = nullptr;
    qz_sandbox* sandbox = nullptr;
    ~Owned() {
        if (sandbox != nullptr) {
            qz_sandbox_free(sandbox);
        }
        if (program != nullptr) {
            qz_program_free(program);
        }
    }
};

} // namespace

int run(std::span<const std::uint8_t> module, const Grants& grants, std::string_view entry) {
    Owned owned;
    char* error = nullptr;
    owned.program = qz_load(module.data(), module.size(), nullptr, 0, false, &error);
    if (owned.program == nullptr) {
        std::fprintf(stderr, "cannot load the embedded module: %s\n", error != nullptr ? error : "unknown error");
        qz_free(error);
        return 2;
    }
    Host host;
    host.grants = &grants;
    host.program = owned.program;
    std::error_code ec;
    host.realRoot = std::filesystem::weakly_canonical(grants.root, ec);
    qz_limits limits{grants.memoryBytes, grants.fuelPerCall, 0, 0};
    owned.sandbox = qz_sandbox_new(owned.program, &limits);
    std::vector<std::unique_ptr<Binding>> bindings;
    std::string missing;
    for (std::uint32_t i = 0; i < qz_program_import_count(owned.program); ++i) {
        if (qz_program_import_is_native(owned.program, i)) {
            continue;
        }
        const char* name = qz_program_import_name(owned.program, i);
        const KitEntry* kit = find(name);
        if (kit == nullptr) {
            missing += missing.empty() ? name : std::string(", ") + name;
            continue;
        }
        bindings.push_back(std::make_unique<Binding>(&host, kit->fn, qz_program_import_result_type(owned.program, i)));
        qz_sandbox_bind_direct(owned.sandbox, name, trampoline, bindings.back().get());
    }
    if (!missing.empty()) {
        std::fprintf(stderr, "the module imports host functions this executable does not provide: %s\n",
                     missing.c_str());
        return 2;
    }
    if (qz_sandbox_start(owned.sandbox) != QZ_OK) {
        std::fprintf(stderr, "%s\n", qz_sandbox_error(owned.sandbox));
        return 1;
    }
    std::string entryName(entry);
    std::uint32_t fn = qz_program_function(owned.program, entryName.c_str());
    if (fn == UINT32_MAX || qz_program_function_arity(owned.program, fn) != 0) {
        std::fprintf(stderr, "the module has no function %s() to run\n", entryName.c_str());
        return 2;
    }
    qz_slot result{};
    qz_status status = qz_sandbox_call(owned.sandbox, fn, nullptr, 0, &result);
    if (host.exitCode) {
        return *host.exitCode;
    }
    if (status != QZ_OK) {
        std::fprintf(stderr, "%s\n", qz_sandbox_error(owned.sandbox));
        return 1;
    }
    std::uint32_t slots = 0;
    qz_kind kind = qz_program_function_result(owned.program, fn, &slots);
    return kind == QZ_KIND_I64 || kind == QZ_KIND_I32 ? static_cast<int>(result.i) : 0;
}

std::string_view text(const qz_slot& slot) {
    std::size_t length = 0;
    const char* p = qz_str_at(&slot, &length);
    return {p, length};
}

bool fail(qz_call* call, const std::string& message) {
    qz_call_fail(call, message.c_str());
    return false;
}

qz_slot makeStr(qz_call* call, std::string_view value) {
    qz_slot out{};
    qz_call_str_into(call, value.data(), value.size(), &out);
    return out;
}

bool makeStrList(qz_call* call, std::uint32_t type, const std::vector<std::string>& items, qz_slot* out) {
    qz_sandbox* sandbox = qz_call_sandbox(call);
    qz_slot list = qz_sandbox_list(sandbox, type);
    for (const std::string& item : items) {
        qz_slot element = makeStr(call, item);
        if (!qz_list_push(sandbox, list, &element, 1)) {
            return fail(call, "out of memory building a list");
        }
    }
    *out = list;
    return true;
}

std::vector<std::string> strList(const qz_slot& list) {
    std::vector<std::string> out;
    std::size_t n = qz_list_length_at(&list);
    for (std::size_t i = 0; i < n; ++i) {
        qz_slot element{};
        qz_list_get_at(&list, i, &element, 1);
        out.emplace_back(text(element));
    }
    return out;
}

} // namespace quartz::embed
