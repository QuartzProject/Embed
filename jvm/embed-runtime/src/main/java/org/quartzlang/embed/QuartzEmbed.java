package org.quartzlang.embed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.quartzlang.embed.api.EmbedLayout;
import org.quartzlang.embed.api.ModuleMetadata;
import org.quartzlang.embed.api.QuartzFunction;
import org.quartzlang.jvm.Limits;
import org.quartzlang.jvm.Program;
import org.quartzlang.jvm.Sandbox;

/** Loads the modules the build packaged and runs them with the kits and host objects the application grants. */
public final class QuartzEmbed {
    private final ClassLoader loader;
    private final Map<String, Kit> kits;
    private final Map<Class<?>, Object> hosts;
    private final Limits limits;
    private final Map<String, Program> programs = new LinkedHashMap<>();

    private QuartzEmbed(Builder b) {
        this.loader = b.loader;
        this.kits = Map.copyOf(b.kits);
        this.hosts = Map.copyOf(b.hosts);
        this.limits = b.limits;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Every module id on the class path, reading the index of every jar. */
    public List<String> modules() {
        Set<String> ids = new LinkedHashSet<>();
        try {
            Enumeration<URL> indexes = loader.getResources(EmbedLayout.INDEX);
            while (indexes.hasMoreElements()) {
                try (InputStream in = indexes.nextElement().openStream()) {
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
                            .filter(l -> !l.isEmpty() && !l.startsWith("#")).forEach(ids::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + EmbedLayout.INDEX, e);
        }
        return new ArrayList<>(ids);
    }

    /** The validated program of module {@code id}, loaded once and shared by every sandbox made from it. */
    public synchronized Program program(String id) {
        Program known = programs.get(id);
        if (known != null) {
            return known;
        }
        byte[] bytes = resource(EmbedLayout.binaryPath(id), id);
        Program program = Program.load(bytes);
        programs.put(id, program);
        return program;
    }

    /** What module {@code id} was built with: its kits and host bindings. */
    public ModuleMetadata metadata(String id) {
        return ModuleMetadata.parse(new String(resource(EmbedLayout.metadataPath(id), id), StandardCharsets.UTF_8));
    }

    /** A started sandbox of module {@code id}. */
    public QuartzModule module(String id) {
        return module(id, limits);
    }

    public QuartzModule module(String id, Limits sandboxLimits) {
        ModuleMetadata metadata = metadata(id);
        for (String kit : metadata.kits()) {
            if (!kits.containsKey(kit)) {
                throw new EmbedException("module " + id + " was built with the " + kit + " kit; grant it with "
                        + "grant(Kits...)");
            }
        }
        Program program = program(id);
        Sandbox sandbox = new Sandbox(program, sandboxLimits);
        Handles handles = new Handles();
        try {
            Set<String> bound = new HashSet<>();
            KitContext context = new KitContext(sandbox, handles, bound);
            for (String kit : metadata.kits()) {
                kits.get(kit).bind(context);
            }
            for (ModuleMetadata.HostBinding binding : metadata.bindings()) {
                HostBinder.bind(sandbox, binding, loader, hosts, handles);
                bound.add(binding.importName());
            }
            List<String> missing = HostBinder.unbound(sandbox, bound);
            if (!missing.isEmpty()) {
                throw new EmbedException("module " + id + " imports host functions nothing binds: " + missing);
            }
            sandbox.start();
        } catch (RuntimeException e) {
            sandbox.close();
            throw e;
        }
        return new QuartzModule(id, program, sandbox, handles);
    }

    /** Runs {@code main} of module {@code id} in a fresh sandbox and returns its exit code. */
    public int run(String id) {
        try (QuartzModule module = module(id)) {
            return module.runMain();
        }
    }

    private byte[] resource(String path, String id) {
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                throw new EmbedException("no Quartz module '" + id + "' on the class path (" + path + ")");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }

    public static final class Builder {
        private ClassLoader loader = defaultLoader();
        private final Map<String, Kit> kits = new LinkedHashMap<>();
        private final Map<Class<?>, Object> hosts = new LinkedHashMap<>();
        private Limits limits = EmbedLimits.PLUGIN;

        private Builder() {}

        /** Where modules, host classes and kits are looked up; the context class loader by default. */
        public Builder classLoader(ClassLoader classLoader) {
            this.loader = Objects.requireNonNull(classLoader);
            return this;
        }

        /** Grants kits to every module; a module may only use the kits it was built with. */
        public Builder grant(Kit... granted) {
            for (Kit kit : granted) {
                kits.put(kit.name(), kit);
            }
            return this;
        }

        /** The instance whose {@link QuartzFunction} instance methods answer the modules' calls. */
        public Builder host(Object instance) {
            hosts.put(instance.getClass(), instance);
            return this;
        }

        /** Budgets of every sandbox; {@link EmbedLimits#PLUGIN} by default. */
        public Builder limits(Limits sandboxLimits) {
            this.limits = Objects.requireNonNull(sandboxLimits);
            return this;
        }

        public QuartzEmbed build() {
            return new QuartzEmbed(this);
        }

        private static ClassLoader defaultLoader() {
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            return context != null ? context : QuartzEmbed.class.getClassLoader();
        }
    }
}
