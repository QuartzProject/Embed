package org.quartzlang.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quartzlang.embed.api.EmbedLayout;
import org.quartzlang.embed.api.ModuleMetadata;
import org.quartzlang.jvm.CompileOptions;
import org.quartzlang.jvm.Program;
import org.quartzlang.jvm.Quartz;
import org.quartzlang.jvm.Source;
import org.quartzlang.jvm.TrapCode;

class QuartzEmbedTest {
    public static final class Greeter {
        private final String greeting;

        public Greeter(String greeting) {
            this.greeting = greeting;
        }

        public String greet(String name, int times) {
            return (greeting + " " + name + " ").repeat(times).strip();
        }
    }

    @Test
    void runsAPackagedModuleWithKitsAndHostMethods(@TempDir Path dir) throws Exception {
        String host = "package org.example.host\n\nfn greet(name: str, times: i32) -> str\n";
        String source = """
                import org.example.host.greet
                import org.quartzlang.embed.fs
                import org.quartzlang.embed.process.{args, exit}

                pub fn hello(name: str) -> str {
                    return greet(name, 2)
                }

                fn fail(n: int) -> int {
                    return 10 / n
                }

                fn main() {
                    if fs.writeText("out/args.txt", "{args()}").isOk() {
                        exit(7)
                    }
                }
                """;
        Path classes = Files.createDirectories(dir.resolve("classes"));
        try (Program program = Quartz.compile(List.of(kit("fs"), kit("process"), Source.of("host.qzi", host),
                Source.of("app.qz", source)), CompileOptions.DEFAULT.withBinary(true))) {
            Files.createDirectories(classes.resolve(EmbedLayout.MODULES));
            Files.write(classes.resolve(EmbedLayout.binaryPath("app")), program.binary().orElseThrow());
        }
        var metadata = new ModuleMetadata(java.util.Set.of("fs", "process"), List.of(new ModuleMetadata.HostBinding(
                "org.example.host.greet", ModuleMetadata.CallKind.INSTANCE, Greeter.class.getName(), "greet",
                "(Ljava/lang/String;I)Ljava/lang/String;")));
        Files.writeString(classes.resolve(EmbedLayout.metadataPath("app")), metadata.write());
        Files.writeString(classes.resolve(EmbedLayout.INDEX), "app\n");

        Path root = dir.resolve("root");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
                getClass().getClassLoader())) {
            QuartzEmbed embed = QuartzEmbed.builder().classLoader(loader).host(new Greeter("hi"))
                    .grant(Kits.fileSystem(root), Kits.process("a", "b")).limits(EmbedLimits.CLI).build();
            assertEquals(List.of("app"), embed.modules());
            try (QuartzModule module = embed.module("app")) {
                assertEquals("hi ada hi ada", module.invoke("hello", "ada"));
                assertEquals(7, module.runMain());
                QuartzScriptException trap = assertThrows(QuartzScriptException.class,
                        () -> module.invoke("fail", 0L));
                assertEquals(TrapCode.DIVIDE_BY_ZERO, trap.code());
                assertTrue(trap.getMessage().contains("fail"), trap.getMessage());
            }
            assertEquals("[a, b]", Files.readString(root.resolve("out/args.txt")));
            EmbedException missing = assertThrows(EmbedException.class,
                    () -> QuartzEmbed.builder().classLoader(loader).build().module("app"));
            assertTrue(missing.getMessage().contains("kit"), missing.getMessage());
        }
    }

    @Test
    void answersAPathOutsideTheGrantedOnesWithAnError(@TempDir Path dir) throws Exception {
        String source = """
                import org.quartzlang.embed.fs

                fn main() -> int {
                    return match fs.writeText("../escape.txt", "x") {
                        Ok(_) => 0
                        Err(_) => 3
                    }
                }
                """;
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes.resolve(EmbedLayout.MODULES));
        try (Program program = Quartz.compile(List.of(kit("fs"), Source.of("app.qz", source)),
                CompileOptions.DEFAULT.withBinary(true))) {
            Files.write(classes.resolve(EmbedLayout.binaryPath("esc")), program.binary().orElseThrow());
        }
        Files.writeString(classes.resolve(EmbedLayout.metadataPath("esc")), "kits=fs\n");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()})) {
            QuartzEmbed embed = QuartzEmbed.builder().classLoader(loader).grant(Kits.fileSystem(dir.resolve("root")))
                    .build();
            assertEquals(3, embed.run("esc"));
        }
        assertTrue(Files.notExists(dir.resolve("escape.txt")));
    }

    private static Source kit(String name) throws Exception {
        try (InputStream in = QuartzEmbed.class.getClassLoader().getResourceAsStream(EmbedLayout.kitInterfacePath(
                name))) {
            return Source.of(name + ".qzi", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
