package org.quartzlang.embed;

import java.util.Set;
import org.quartzlang.jvm.HostFunction;
import org.quartzlang.jvm.Sandbox;

/** What a {@link Kit} binds into: one module's sandbox and handle table. */
public final class KitContext {
    private final Sandbox sandbox;
    private final Handles handles;
    private final Set<String> bound;

    KitContext(Sandbox sandbox, Handles handles, Set<String> bound) {
        this.sandbox = sandbox;
        this.handles = handles;
        this.bound = bound;
    }

    public Handles handles() {
        return handles;
    }

    /** Binds {@code name} when the module imports it; a module compiled without the kit imports nothing of it. */
    public void bind(String name, HostFunction function) {
        if (sandbox.program().findImport(name).isPresent()) {
            sandbox.bind(name, function);
            bound.add(name);
        }
    }
}
