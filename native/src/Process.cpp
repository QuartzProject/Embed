#include "Host.hpp"

#include <cstdlib>
#include <string>
#include <vector>

#ifndef _WIN32
#include <cerrno>
#include <poll.h>
#include <sys/wait.h>
#include <unistd.h>
#endif

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
    *result = makeStr(call, value);
    return true;
}

bool exitProgram(qz_call* call, const qz_slot* a, qz_slot*, Host& host, std::uint32_t) {
    host.exitCode = static_cast<int>(a[0].i);
    return fail(call, "exit " + std::to_string(a[0].i));
}

#ifndef _WIN32

/// Runs `command`; with `capture`, collects standard output and error through pipes read with poll.
bool spawn(qz_call* call, const qz_slot* a, bool capture, ExecResult& out) {
    std::vector<std::string> command = strList(a[0]);
    if (command.empty()) {
        return fail(call, "an empty command");
    }
    std::string dir = a[1].p != nullptr ? std::string(text(a[1])) : std::string();
    int pipes[2][2] = {{-1, -1}, {-1, -1}};
    if (capture && (pipe(pipes[0]) != 0 || pipe(pipes[1]) != 0)) {
        return fail(call, "cannot create a pipe");
    }
    std::vector<char*> argv;
    for (std::string& s : command) {
        argv.push_back(s.data());
    }
    argv.push_back(nullptr);
    pid_t pid = fork();
    if (pid < 0) {
        return fail(call, "cannot start " + command[0]);
    }
    if (pid == 0) {
        if (capture) {
            dup2(pipes[0][1], STDOUT_FILENO);
            dup2(pipes[1][1], STDERR_FILENO);
            close(pipes[0][0]);
            close(pipes[1][0]);
            close(STDIN_FILENO);
        }
        if (!dir.empty() && chdir(dir.c_str()) != 0) {
            _exit(127);
        }
        execvp(argv[0], argv.data());
        _exit(127);
    }
    if (capture) {
        close(pipes[0][1]);
        close(pipes[1][1]);
        pollfd fds[2] = {{pipes[0][0], POLLIN, 0}, {pipes[1][0], POLLIN, 0}};
        std::string* sinks[2] = {&out.out, &out.err};
        int open = 2;
        char buffer[65536];
        while (open > 0) {
            if (poll(fds, 2, -1) < 0 && errno != EINTR) {
                break;
            }
            for (int i = 0; i < 2; ++i) {
                if (fds[i].fd < 0 || fds[i].revents == 0) {
                    continue;
                }
                ssize_t n = read(fds[i].fd, buffer, sizeof buffer);
                if (n > 0) {
                    sinks[i]->append(buffer, static_cast<std::size_t>(n));
                } else if (n == 0 || errno != EINTR) {
                    close(fds[i].fd);
                    fds[i].fd = -1;
                    --open;
                }
            }
        }
    }
    int status = 0;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
    }
    out.code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
    return true;
}

#else

bool spawn(qz_call* call, const qz_slot*, bool, ExecResult&) {
    return fail(call, "running programs is not available on this platform yet");
}

#endif

bool run(qz_call* call, const qz_slot* a, qz_slot* result, Host&, std::uint32_t) {
    ExecResult r;
    if (!spawn(call, a, false, r)) {
        return false;
    }
    result->i = r.code;
    return true;
}

bool exec(qz_call* call, const qz_slot* a, qz_slot* result, Host& host, std::uint32_t) {
    ExecResult r;
    if (!spawn(call, a, true, r)) {
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
    return r != nullptr && ((*result = makeStr(call, r->out)), true);
}

bool execStderr(qz_call* call, const qz_slot* a, qz_slot* result, Host& host, std::uint32_t) {
    const ExecResult* r = execOf(call, host, a[0]);
    return r != nullptr && ((*result = makeStr(call, r->err)), true);
}

constexpr KitEntry kEntries[] = {
    {"org.quartzlang.embed.process.args", args},
    {"org.quartzlang.embed.process.env", env},
    {"org.quartzlang.embed.process.exit", exitProgram},
    {"org.quartzlang.embed.process.run", run},
    {"org.quartzlang.embed.process.exec", exec},
    {"Exec.code", execCode},
    {"Exec.stdout", execStdout},
    {"Exec.stderr", execStderr},
};

} // namespace

std::span<const KitEntry> processKit() { return kEntries; }

} // namespace quartz::embed
