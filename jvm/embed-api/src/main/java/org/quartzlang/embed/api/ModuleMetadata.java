package org.quartzlang.embed.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a module needs at run time: the kits it was compiled with and the Java methods behind its host imports.
 *
 * @param kits kit names ({@code fs}, {@code process}...), which the application has to grant
 * @param bindings one per {@link QuartzFunction} method the module imports
 */
public record ModuleMetadata(Set<String> kits, List<HostBinding> bindings) {
    /** How a method is called: free function, method of a handle, or instance method of a host object. */
    public enum CallKind {
        STATIC,
        HANDLE,
        INSTANCE
    }

    /**
     * One host import and the method that answers it.
     *
     * @param importName the name the program imports it by ({@code org.example.greet}, {@code Player.name})
     * @param owner the binary name of the declaring class
     * @param method the method name
     * @param descriptor the JVM method descriptor, which tells overloads apart
     */
    public record HostBinding(String importName, CallKind kind, String owner, String method, String descriptor) {}

    public ModuleMetadata {
        kits = Set.copyOf(new LinkedHashSet<>(kits));
        bindings = List.copyOf(bindings);
    }

    public static ModuleMetadata read(InputStream in) {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Properties p = new Properties();
            p.load(reader);
            return of(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static ModuleMetadata parse(String text) {
        try {
            Properties p = new Properties();
            p.load(new StringReader(text));
            return of(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ModuleMetadata of(Properties p) {
        Set<String> kits = new LinkedHashSet<>();
        String list = p.getProperty("kits", "").strip();
        if (!list.isEmpty()) {
            kits.addAll(Arrays.asList(list.split(",")));
        }
        List<HostBinding> bindings = new ArrayList<>();
        for (String key : new TreeSet<>(p.stringPropertyNames())) {
            if (!key.startsWith("bind.")) {
                continue;
            }
            String[] parts = p.getProperty(key).strip().split(" ");
            if (parts.length != 4) {
                throw new IllegalArgumentException("malformed binding " + key + "=" + p.getProperty(key));
            }
            bindings.add(new HostBinding(key.substring(5), CallKind.valueOf(parts[0]), parts[1], parts[2], parts[3]));
        }
        return new ModuleMetadata(kits, bindings);
    }

    /** The properties text the build writes, in a stable order so the output is reproducible. */
    public String write() {
        StringBuilder out = new StringBuilder("kits=").append(String.join(",", new TreeSet<>(kits))).append('\n');
        for (HostBinding b : bindings.stream().sorted((x, y) -> x.importName().compareTo(y.importName())).toList()) {
            out.append("bind.").append(b.importName()).append('=').append(b.kind()).append(' ').append(b.owner())
                    .append(' ').append(b.method()).append(' ').append(b.descriptor()).append('\n');
        }
        return out.toString();
    }
}
