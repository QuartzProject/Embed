package org.quartzlang.embed;

/** A capability an application grants to modules: a host interface in Quartz and the Java code that answers it. */
public interface Kit {
    /** The kit's name, as the build's {@code kits} list and the module metadata spell it. */
    String name();

    /** Binds the kit's functions the module imports. */
    void bind(KitContext context);
}
