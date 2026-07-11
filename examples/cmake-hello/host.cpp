#include <quartz/quartz.h>

#include <cstdint>
#include <cstdio>
#include <string_view>

extern "C" const std::uint8_t quartz_module_scores[];
extern "C" const std::size_t quartz_module_scores_size;

static bool bonus(qz_call*, const qz_slot* args, qz_slot* result, void*) {
    std::size_t length = 0;
    const char* text = qz_str_at(&args[0], &length);
    std::string_view name(text, length);
    result->i = name == "ada" ? 100 : 0;
    return true;
}

int main() {
    char* error = nullptr;
    qz_program* program = qz_load(quartz_module_scores, quartz_module_scores_size, nullptr, 0, false, &error);
    if (program == nullptr) {
        std::fprintf(stderr, "%s\n", error);
        return 1;
    }
    qz_sandbox* sandbox = qz_sandbox_new(program, nullptr);
    qz_sandbox_bind_direct(sandbox, "bonus", bonus, nullptr);
    if (qz_sandbox_start(sandbox) != QZ_OK) {
        std::fprintf(stderr, "%s\n", qz_sandbox_error(sandbox));
        return 1;
    }
    qz_slot args[2];
    args[0] = qz_sandbox_str(sandbox, "ada", 3);
    args[1].i = 21;
    qz_slot result{};
    if (qz_sandbox_call(sandbox, qz_program_function(program, "score"), args, 2, &result) != QZ_OK) {
        std::fprintf(stderr, "%s\n", qz_sandbox_error(sandbox));
        return 1;
    }
    std::printf("score = %lld\n", static_cast<long long>(result.i));
    qz_sandbox_free(sandbox);
    qz_program_free(program);
}
