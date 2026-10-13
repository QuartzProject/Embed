#include "Spawn.hpp"

#ifndef _WIN32
#include <cerrno>
#include <csignal>
#include <poll.h>
#include <sys/wait.h>
#include <unistd.h>
#endif

namespace quartz::embed {

#ifndef _WIN32

namespace {

void closeFd(int& fd) {
    if (fd >= 0) {
        close(fd);
        fd = -1;
    }
}

} // namespace

std::optional<std::string> spawn(const std::vector<std::string>& command, const SpawnOptions& options,
                                 ExecResult& out) {
    if (command.empty()) {
        return "an empty command";
    }
    int outPipe[2] = {-1, -1};
    int errPipe[2] = {-1, -1};
    int inPipe[2] = {-1, -1};
    if ((options.capture && (pipe(outPipe) != 0 || pipe(errPipe) != 0)) || (options.input && pipe(inPipe) != 0)) {
        return "cannot create a pipe";
    }
    std::vector<std::string> args = command;
    std::vector<char*> argv;
    for (std::string& s : args) {
        argv.push_back(s.data());
    }
    argv.push_back(nullptr);
    pid_t pid = fork();
    if (pid < 0) {
        return "cannot start " + command[0];
    }
    if (pid == 0) {
        if (options.capture) {
            dup2(outPipe[1], STDOUT_FILENO);
            dup2(errPipe[1], STDERR_FILENO);
            close(outPipe[0]);
            close(errPipe[0]);
        }
        if (options.input) {
            dup2(inPipe[0], STDIN_FILENO);
            close(inPipe[1]);
        } else if (options.capture) {
            close(STDIN_FILENO);
        }
        if (!options.dir.empty() && chdir(options.dir.c_str()) != 0) {
            _exit(127);
        }
        execvp(argv[0], argv.data());
        _exit(127);
    }
    closeFd(outPipe[1]);
    closeFd(errPipe[1]);
    closeFd(inPipe[0]);
    // A reader that quits early must not kill this process through SIGPIPE.
    auto previous = std::signal(SIGPIPE, SIG_IGN);
    std::string_view input = options.input.value_or(std::string_view());
    pollfd fds[3] = {{outPipe[0], POLLIN, 0}, {errPipe[0], POLLIN, 0}, {inPipe[1], POLLOUT, 0}};
    if (input.empty()) {
        closeFd(inPipe[1]);
        fds[2].fd = -1;
    }
    std::string* sinks[2] = {&out.out, &out.err};
    char buffer[65536];
    while (fds[0].fd >= 0 || fds[1].fd >= 0 || fds[2].fd >= 0) {
        if (poll(fds, 3, -1) < 0) {
            if (errno == EINTR) {
                continue;
            }
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
                closeFd(fds[i].fd);
            }
        }
        if (fds[2].fd >= 0 && fds[2].revents != 0) {
            ssize_t n = write(fds[2].fd, input.data(), input.size());
            if (n > 0) {
                input.remove_prefix(static_cast<std::size_t>(n));
            }
            if ((n < 0 && errno != EINTR && errno != EAGAIN) || input.empty()) {
                closeFd(fds[2].fd);
                inPipe[1] = -1;
            }
        }
    }
    closeFd(inPipe[1]);
    int status = 0;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
    }
    std::signal(SIGPIPE, previous);
    out.code = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
    return std::nullopt;
}

#else

std::optional<std::string> spawn(const std::vector<std::string>&, const SpawnOptions&, ExecResult&) {
    return "running programs is not available on this platform yet";
}

#endif

} // namespace quartz::embed
