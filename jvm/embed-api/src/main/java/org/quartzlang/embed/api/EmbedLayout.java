package org.quartzlang.embed.api;

import java.util.List;

/** Where the build puts compiled modules inside a jar; the Gradle plugin and the runtime share these paths. */
public final class EmbedLayout {
    public static final String ROOT = "META-INF/quartz";
    /** One module id per line, for every module one jar carries. */
    public static final String INDEX = ROOT + "/modules.index";
    public static final String MODULES = ROOT + "/modules";
    /** Where kit interfaces live in the runtime jar. */
    public static final String KITS = "org/quartzlang/embed/kits";
    /** Every kit Embed ships, by name. */
    public static final List<String> KIT_NAMES = List.of("fs", "terminal", "process", "http", "archives");

    private EmbedLayout() {}

    /** The validated .qzb of module {@code id}. */
    public static String binaryPath(String id) {
        return MODULES + "/" + validate(id) + ".qzb";
    }

    /** The module's kits and host bindings, as properties. */
    public static String metadataPath(String id) {
        return MODULES + "/" + validate(id) + ".properties";
    }

    public static String kitInterfacePath(String kit) {
        return KITS + "/" + validate(kit) + ".qzi";
    }

    /** Checks that {@code id} names a resource without leaving its directory, and returns it. */
    public static String validate(String id) {
        if (id.isEmpty() || !id.matches("[A-Za-z0-9_.-]+") || id.startsWith(".")) {
            throw new IllegalArgumentException("'" + id + "' is not a module id (letters, digits, '.', '-', '_')");
        }
        return id;
    }
}
