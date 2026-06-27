package org.quartzlang.embed;

import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import org.quartzlang.jvm.Kind;
import org.quartzlang.jvm.Type;

/** Between the boxed values of the bindings (Long, Double, String, List...) and the types a Java method declares. */
public final class Conversions {
    private Conversions() {}

    /** A value the bindings read as {@code quartz}, converted to the Java type {@code target}. */
    public static Object toJava(Object boxed, Type quartz, java.lang.reflect.Type target, Handles handles) {
        if (boxed == null) {
            return null;
        }
        Kind kind = quartz.kind();
        if (kind == Kind.NULLABLE) {
            return toJava(boxed, quartz.element(), target, handles);
        }
        Class<?> raw = rawClass(target);
        if (kind == Kind.HANDLE) {
            long id = ((Number) boxed).longValue();
            return raw == long.class || raw == Long.class ? id : handles.get(id, raw);
        }
        if (kind == Kind.LIST) {
            java.lang.reflect.Type element = target instanceof ParameterizedType p ? p.getActualTypeArguments()[0]
                    : Object.class;
            List<?> in = (List<?>) boxed;
            List<Object> out = new ArrayList<>(in.size());
            for (Object e : in) {
                out.add(toJava(e, quartz.element(), element, handles));
            }
            return out;
        }
        if (kind == Kind.CHAR && (raw == char.class || raw == Character.class)) {
            int cp = (Integer) boxed;
            if (cp > 0xFFFF) {
                throw new IllegalArgumentException("U+" + Integer.toHexString(cp) + " does not fit in a Java char");
            }
            return (char) cp;
        }
        if (boxed instanceof Number n) {
            if (raw == int.class || raw == Integer.class) {
                return n.intValue();
            }
            if (raw == long.class || raw == Long.class) {
                return n.longValue();
            }
            if (raw == short.class || raw == Short.class) {
                return n.shortValue();
            }
            if (raw == byte.class || raw == Byte.class) {
                return n.byteValue();
            }
            if (raw == double.class || raw == Double.class) {
                return n.doubleValue();
            }
            if (raw == float.class || raw == Float.class) {
                return n.floatValue();
            }
        }
        return boxed;
    }

    /** A Java value as the bindings take it for {@code quartz}: handles become ids, lists convert their elements. */
    public static Object toQuartz(Object value, Type quartz, Handles handles) {
        if (value == null) {
            return null;
        }
        Kind kind = quartz.kind();
        if (kind == Kind.NULLABLE) {
            return toQuartz(value, quartz.element(), handles);
        }
        if (kind == Kind.HANDLE) {
            return value instanceof Long l ? l : handles.idOf(value);
        }
        if (kind == Kind.LIST && value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object e : list) {
                out.add(toQuartz(e, quartz.element(), handles));
            }
            return out;
        }
        if (kind == Kind.F32 && value instanceof Float f) {
            return f.doubleValue();
        }
        if (kind == Kind.CHAR && value instanceof Character c) {
            return (int) c;
        }
        return value;
    }

    static Class<?> rawClass(java.lang.reflect.Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType p) {
            return (Class<?>) p.getRawType();
        }
        return Object.class;
    }
}
