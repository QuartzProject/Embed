package org.quartzlang.embed;

import org.quartzlang.jvm.Type;
import org.quartzlang.jvm.Values;

/**
 * Handles between Java objects and their ids, wherever they sit in a value: alone, in lists, maps, nullables,
 * results, struct fields, tuples and enum payloads. Values without handles go to the bindings unchanged, which convert
 * records, data classes, enums and sealed types themselves.
 */
public final class Conversions {
    private Conversions() {}

    /** True when a value of {@code type} can hold a handle somewhere inside. */
    public static boolean hasHandle(Type type) {
        return type.holdsHandles();
    }

    /** A value the bindings boxed for {@code quartz}, converted to {@code target} with its handles as their objects. */
    public static Object toJava(Object boxed, Type quartz, java.lang.reflect.Type target, Handles handles) {
        return Values.to(boxed, quartz, target, handles.mapper());
    }

    /** A Java value as the bindings take it for {@code quartz}: handles become ids wherever they are. */
    public static Object toQuartz(Object value, Type quartz, Handles handles) {
        if (value == null || !quartz.holdsHandles()) {
            return value;
        }
        return Values.from(value, quartz, handles.mapper());
    }
}
