package org.quartzlang.embed;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.quartzlang.jvm.Kind;
import org.quartzlang.jvm.Result;
import org.quartzlang.jvm.Struct;
import org.quartzlang.jvm.Type;

/**
 * Handles between Java objects and their ids, wherever they sit in a value: alone, in lists, maps, nullables,
 * results and struct fields. Values without handles go to the bindings unchanged, which convert records, data
 * classes, enums and sealed types themselves.
 */
public final class Conversions {
    private static final Map<Type, Boolean> HAS_HANDLE = java.util.Collections.synchronizedMap(new IdentityHashMap<>());

    private Conversions() {}

    /** True when a value of {@code type} can hold a handle somewhere inside. */
    public static boolean hasHandle(Type type) {
        Boolean known = HAS_HANDLE.get(type);
        if (known != null) {
            return known;
        }
        boolean result = scan(type, new IdentityHashMap<>());
        HAS_HANDLE.put(type, result);
        return result;
    }

    private static boolean scan(Type type, Map<Type, Boolean> seen) {
        if (type == null || seen.put(type, Boolean.TRUE) != null) {
            return false;
        }
        if (type.kind() == Kind.HANDLE) {
            return true;
        }
        if (scan(type.element(), seen) || scan(type.key(), seen)) {
            return true;
        }
        for (Type.Field f : type.fields()) {
            if (scan(f.type(), seen)) {
                return true;
            }
        }
        for (Type.Case c : type.cases()) {
            for (Type.Field f : c.payload()) {
                if (scan(f.type(), seen)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A value the bindings boxed for {@code quartz} with its handles replaced by their objects, ready for
     * {@code target}: a struct becomes the record or data class, a list or map keeps its shape.
     */
    public static Object toJava(Object boxed, Type quartz, java.lang.reflect.Type target, Handles handles) {
        if (boxed == null) {
            return null;
        }
        Class<?> raw = rawClass(target);
        return switch (quartz.kind()) {
            case NULLABLE -> toJava(boxed, quartz.element(), target, handles);
            case HANDLE -> {
                long id = ((Number) boxed).longValue();
                yield raw == long.class || raw == Long.class ? id : handles.get(id, raw);
            }
            case LIST -> {
                java.lang.reflect.Type element = argument(target, 0);
                List<Object> out = new ArrayList<>();
                for (Object e : (List<?>) boxed) {
                    out.add(toJava(e, quartz.element(), element, handles));
                }
                yield out;
            }
            case MAP -> {
                Map<Object, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) boxed).entrySet()) {
                    out.put(toJava(e.getKey(), quartz.key(), argument(target, 0), handles),
                            toJava(e.getValue(), quartz.element(), argument(target, 1), handles));
                }
                yield out;
            }
            case STRUCT -> {
                if (!hasHandle(quartz) || !(boxed instanceof Struct struct)) {
                    yield boxed;
                }
                Map<String, Object> fields = new LinkedHashMap<>();
                for (int i = 0; i < quartz.fields().size(); ++i) {
                    Type.Field f = quartz.fields().get(i);
                    fields.put(f.name(), toJava(struct.get(i), f.type(), componentType(raw, f.name()), handles));
                }
                Struct resolved = Struct.of(quartz.name(), fields);
                yield raw == Object.class || raw == Struct.class ? resolved : resolved.to(raw);
            }
            case ENUM -> {
                if (quartz.isResult() && boxed instanceof Result<?, ?> r && hasHandle(quartz)) {
                    yield switch (r) {
                        case Result.Ok<?, ?> ok -> Result.ok(toJava(ok.value(), quartz.cases().get(0).payload().get(0)
                                .type(), argument(target, 0), handles));
                        case Result.Err<?, ?> err -> Result.err(toJava(err.error(), quartz.cases().get(1).payload()
                                .get(0).type(), argument(target, 1), handles));
                    };
                }
                yield boxed;
            }
            default -> boxed;
        };
    }

    /** A Java value as the bindings take it for {@code quartz}: handles become ids wherever they are. */
    public static Object toQuartz(Object value, Type quartz, Handles handles) {
        if (value == null || !hasHandle(quartz)) {
            return value;
        }
        return switch (quartz.kind()) {
            case NULLABLE -> toQuartz(value, quartz.element(), handles);
            case HANDLE -> value instanceof Long l ? l : handles.idOf(value);
            case LIST -> {
                List<Object> out = new ArrayList<>();
                for (Object e : (Iterable<?>) value) {
                    out.add(toQuartz(e, quartz.element(), handles));
                }
                yield out;
            }
            case MAP -> {
                Map<Object, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                    out.put(toQuartz(e.getKey(), quartz.key(), handles), toQuartz(e.getValue(), quartz.element(),
                            handles));
                }
                yield out;
            }
            case STRUCT -> {
                if (value instanceof Struct) {
                    yield value;
                }
                Map<String, Object> fields = new LinkedHashMap<>();
                for (Type.Field f : quartz.fields()) {
                    fields.put(f.name(), toQuartz(read(value, f.name()), f.type(), handles));
                }
                yield Struct.of(quartz.name(), fields);
            }
            case ENUM -> {
                if (quartz.isResult() && value instanceof Result<?, ?> r) {
                    yield switch (r) {
                        case Result.Ok<?, ?> ok -> Result.ok(toQuartz(ok.value(), quartz.cases().get(0).payload().get(0)
                                .type(), handles));
                        case Result.Err<?, ?> err -> Result.err(toQuartz(err.error(), quartz.cases().get(1).payload()
                                .get(0).type(), handles));
                    };
                }
                throw new IllegalArgumentException("enum variants that carry handles cannot cross: " + quartz.name());
            }
            default -> value;
        };
    }

    /** A field of a record, a Kotlin data class or a bean, by its Quartz name. */
    private static Object read(Object value, String name) {
        if (value instanceof Map<?, ?> map) {
            return map.get(name);
        }
        Class<?> c = value.getClass();
        try {
            if (c.isRecord()) {
                for (RecordComponent rc : c.getRecordComponents()) {
                    if (rc.getName().equals(name)) {
                        Method accessor = rc.getAccessor();
                        accessor.setAccessible(true);
                        return accessor.invoke(value);
                    }
                }
            }
            String bean = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            for (String candidate : List.of(name, bean)) {
                try {
                    Method m = c.getMethod(candidate);
                    m.setAccessible(true);
                    return m.invoke(value);
                } catch (NoSuchMethodException ignored) {
                    // try the next spelling
                }
            }
            var field = c.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("cannot read field " + name + " of " + c.getName(), e);
        }
    }

    private static java.lang.reflect.Type componentType(Class<?> owner, String name) {
        if (owner.isRecord()) {
            for (RecordComponent rc : owner.getRecordComponents()) {
                if (rc.getName().equals(name)) {
                    return rc.getGenericType();
                }
            }
        }
        try {
            return owner.getDeclaredField(name).getGenericType();
        } catch (NoSuchFieldException e) {
            return Object.class;
        }
    }

    private static java.lang.reflect.Type argument(java.lang.reflect.Type type, int index) {
        if (type instanceof ParameterizedType p && p.getActualTypeArguments().length > index) {
            return p.getActualTypeArguments()[index];
        }
        return Object.class;
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
