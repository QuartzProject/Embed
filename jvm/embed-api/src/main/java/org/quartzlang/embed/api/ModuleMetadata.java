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
 * What a module needs at run time and what it offers: the kits its code calls, the Java methods behind its host
 * imports, and its functions that carry attributes such as {@code @command} or {@code @on}.
 *
 * @param kits kit names ({@code fs}, {@code process}...) whose functions the module calls; the application grants them
 * @param bindings one per {@link QuartzFunction} method the module calls
 * @param functions one per attribute of an exported function, in source order
 */
public record ModuleMetadata(Set<String> kits, List<HostBinding> bindings, List<AttributedFunction> functions) {
    /** How a method is called: free function, method of a handle, or instance method of a host object. */
    public enum CallKind {
        STATIC,
        HANDLE,
        INSTANCE
    }

    /**
     * One host import and the method that answers it.
     *
     * @param importName the name the program imports it by ({@code org.example.greet}, {@code org.example.Player.name})
     * @param owner the binary name of the declaring class
     * @param method the method name
     * @param descriptor the JVM method descriptor, which tells overloads apart
     */
    public record HostBinding(String importName, CallKind kind, String owner, String method, String descriptor) {}

    /**
     * An exported function and one of its attributes.
     *
     * @param function its name with the package, as {@code QuartzModule.invoke} takes it
     * @param parameters the names of what a caller passes ({@code state} parameters are not among them)
     * @param attribute the attribute
     * @param filter the function that tests the first argument, from {@code @on(..., where = expr)}; empty otherwise
     */
    public record AttributedFunction(String function, List<String> parameters, QuartzAttribute attribute,
            String filter) {
        public AttributedFunction {
            parameters = List.copyOf(parameters);
        }

        /** The name without its package. */
        public String simpleName() {
            return function.substring(function.lastIndexOf('.') + 1);
        }
    }

    public ModuleMetadata {
        kits = Set.copyOf(new LinkedHashSet<>(kits));
        bindings = List.copyOf(bindings);
        functions = List.copyOf(functions);
    }

    public ModuleMetadata(Set<String> kits, List<HostBinding> bindings) {
        this(kits, bindings, List.of());
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
        Set<String> kits = new LinkedHashSet<>(split(p.getProperty("kits", "")));
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
        List<AttributedFunction> functions = new ArrayList<>();
        int count = Integer.parseInt(p.getProperty("attributes", "0"));
        for (int i = 0; i < count; ++i) {
            String at = "attribute." + i + ".";
            List<QuartzAttribute.Arg> args = new ArrayList<>();
            int argCount = Integer.parseInt(p.getProperty(at + "args", "0"));
            for (int a = 0; a < argCount; ++a) {
                String arg = at + "arg." + a + ".";
                args.add(new QuartzAttribute.Arg(p.getProperty(arg + "key", ""),
                        QuartzAttribute.Kind.valueOf(p.getProperty(arg + "kind")), p.getProperty(arg + "value", "")));
            }
            functions.add(new AttributedFunction(p.getProperty(at + "function"), split(p.getProperty(at + "params", "")),
                    new QuartzAttribute(p.getProperty(at + "name"), args), p.getProperty(at + "filter", "")));
        }
        return new ModuleMetadata(kits, bindings, functions);
    }

    private static List<String> split(String list) {
        String s = list.strip();
        return s.isEmpty() ? List.of() : Arrays.asList(s.split(","));
    }

    /** The properties text the build writes, in a stable order so the output is reproducible. */
    public String write() {
        StringBuilder out = new StringBuilder("kits=").append(String.join(",", new TreeSet<>(kits))).append('\n');
        for (HostBinding b : bindings.stream().sorted((x, y) -> x.importName().compareTo(y.importName())).toList()) {
            line(out, "bind." + b.importName(), b.kind() + " " + b.owner() + " " + b.method() + " " + b.descriptor());
        }
        if (!functions.isEmpty()) {
            line(out, "attributes", Integer.toString(functions.size()));
        }
        for (int i = 0; i < functions.size(); ++i) {
            AttributedFunction f = functions.get(i);
            String at = "attribute." + i + ".";
            line(out, at + "function", f.function());
            line(out, at + "name", f.attribute().name());
            line(out, at + "params", String.join(",", f.parameters()));
            if (!f.filter().isEmpty()) {
                line(out, at + "filter", f.filter());
            }
            line(out, at + "args", Integer.toString(f.attribute().args().size()));
            for (int a = 0; a < f.attribute().args().size(); ++a) {
                QuartzAttribute.Arg arg = f.attribute().args().get(a);
                String key = at + "arg." + a + ".";
                if (!arg.key().isEmpty()) {
                    line(out, key + "key", arg.key());
                }
                line(out, key + "kind", arg.kind().name());
                line(out, key + "value", arg.value());
            }
        }
        return out.toString();
    }

    private static void line(StringBuilder out, String key, String value) {
        out.append(escape(key, true)).append('=').append(escape(value, false)).append('\n');
    }

    private static String escape(String text, boolean key) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '=', ':', '#', '!' -> out.append('\\').append(c);
                case ' ' -> out.append(key || i == 0 ? "\\ " : " ");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
