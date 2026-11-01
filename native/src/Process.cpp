#include "Spawn.hpp"

#include <cstdlib>
#include <string>
#include <vector>

namespace quartz::embed {

namespace {

bool args(qz_call* call, const qz_slot*, qz_slot* result, Host& host, std::uint32_t type) {
    return makeStrList(call, type, host.grants->args, result);
}

bool env(qz_call* call, const qz_slot* a, qz_slot* result, Host&, std::uint32_t) {
    std::string name(text(a[0]));
    const char* value = std::getenv(name.c_str());
    if (value == nullptr) {
        result->p = nullptr;
        return true;
    }
    *result = makeStrLossy(call, value);
    return true;
}

bool exitProgram(qz_call* call, const qz_slot* a, qz_slot*, Host& host, std::uint32_t) {
    host.exitCode = static_cast<int>(a[0].i);
    return fail(call, "exit " + std::to_string(a[0].i));
}

bool start(qz_call* call, const qz_slot* a, bool capture, ExecResult& r) {
    SpawnOptions options;
    options.capture = capture;
    if (a[1].p != nullptr) {
        options.dir = std::string(text(a[1]));
    }
    if (auto why = spawn(strList(a[0]), options, r)) {
        return fail(call, *why);
    }
    return true;
}

bool run(qz_call* call, const qz_slot* a, qz_slot* result, Host&, std::uint32_t) {
    ExecResult r;
    if (!start(call, a, false, r)) {
        return false;
    }
    result->i = r.code;
    return true;
}

bool exec(qz_call* call, const qz_slot* a, qz_slot* result, Host& host, std::uint32_t) {
    ExecResult r;
    if (!start(call, a, true, r)) {
        return false;
    }
    host.execs.push_back(std::move(r));
    result->u = host.execs.size();
    return true;
}

const ExecResult* execOf(qz_call* call, Host& host, const qz_slot& handle) {
    if (handle.u == 0 || handle.u > host.execs.size()) {
        fail(call, "not an Exec handle");
        return nullptr;
    }
    return &host.execs[handle.u - 1];
}

bool execCode(qz_call* call, const qz_slot* a, qz_slot* result, Host& host, std::uint32_t) {
    const ExecResult* r = execOf(call, host, a[0]);
    return r != nullptr && ((result->i = r->code), true);
}

bool execStdout(qz_call* call, const qz_slot* a, qz_slot* result, Host& host, std::uint32_t) {
    const ExecResult* r = execOf(call, host, a[0]);
    return r != nullptr && ((*result = makeStrLossy(call, r->out)), true);
}

bool execStderr(qz_call* call, const qz_slot* a, qz_slot* result, Host& host, std::uint32_t) {
    const ExecResult* r = execOf(call, host, a[0]);
    return r != nullptr && ((*result = makeStrLossy(call, r->err)), true);
}

constexpr KitEntry kEntries[] = {
    {"org.quartzlang.embed.process.args", args},
    {"org.quartzlang.embed.process.env", env},
    {"org.quartzlang.embed.process.exit", exitProgram},
    {"org.quartzlang.embed.process.run", run},
    {"org.quartzlang.embed.process.exec", exec},
    {"org.quartzlang.embed.process.Exec.code", execCode},
    {"org.quartzlang.embed.process.Exec.stdout", execStdout},
    {"org.quartzlang.embed.process.Exec.stderr", execStderr},
};

} // namespace

std::span<const KitEntry> processKit() { return kEntries; }

} // namespace quartz::embed
