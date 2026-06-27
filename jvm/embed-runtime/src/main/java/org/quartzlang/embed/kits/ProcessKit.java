package org.quartzlang.embed.kits;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.quartzlang.embed.ExitRequest;
import org.quartzlang.embed.Kit;
import org.quartzlang.embed.KitContext;
import org.quartzlang.jvm.HostCall;

/** {@code org.quartzlang.embed.process}: arguments, environment, exit and other programs. */
public final class ProcessKit implements Kit {
    private static final String P = "org.quartzlang.embed.process.";
    private final List<String> args;

    /** What {@code exec} kept from a finished program. */
    public record Exec(long code, String stdout, String stderr) {}

    public ProcessKit(List<String> args) {
        this.args = List.copyOf(args);
    }

    @Override
    public String name() {
        return "process";
    }

    @Override
    public void bind(KitContext k) {
        k.bind(P + "args", c -> args);
        k.bind(P + "env", c -> System.getenv(c.stringArg(0)));
        k.bind(P + "exit", c -> {
            throw new ExitRequest(Math.toIntExact(c.longArg(0)));
        });
        k.bind(P + "run", c -> {
            ProcessBuilder builder = builder(c).inheritIO();
            return (long) builder.start().waitFor();
        });
        k.bind(P + "exec", c -> {
            Process process = builder(c).start();
            process.getOutputStream().close();
            CompletableFuture<String> err = CompletableFuture.supplyAsync(() -> drain(process.getErrorStream()));
            String out = drain(process.getInputStream());
            return k.handles().idOf(new Exec(process.waitFor(), out, err.join()));
        });
        k.bind("Exec.code", c -> exec(k, c).code());
        k.bind("Exec.stdout", c -> exec(k, c).stdout());
        k.bind("Exec.stderr", c -> exec(k, c).stderr());
    }

    private static Exec exec(KitContext k, HostCall c) {
        return k.handles().get(c.longArg(0), Exec.class);
    }

    private static ProcessBuilder builder(HostCall c) {
        List<String> command = c.listArg(0).stream().map(String.class::cast).toList();
        if (command.isEmpty()) {
            throw new IllegalArgumentException("an empty command");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        Object dir = c.arg(1);
        if (dir != null) {
            builder.directory(Path.of((String) dir).toFile());
        }
        return builder;
    }

    private static String drain(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
