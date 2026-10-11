package org.quartzlang.embed;

import java.util.List;
import org.quartzlang.embed.api.ModuleMetadata;
import org.quartzlang.embed.api.ModuleMetadata.AttributedFunction;
import org.quartzlang.jvm.Export;
import org.quartzlang.jvm.Kind;
import org.quartzlang.jvm.Program;
import org.quartzlang.jvm.Sandbox;
import org.quartzlang.jvm.TaskOutcome;
import org.quartzlang.jvm.TrapException;
import org.quartzlang.jvm.Type;
import org.quartzlang.jvm.Value;

/** One packaged module running in its own sandbox, with its kits and host functions bound. One thread at a time. */
public final class QuartzModule implements AutoCloseable {
    private final String id;
    private final Program program;
    private final Sandbox sandbox;
    private final Handles handles;
    private final ModuleMetadata metadata;

    QuartzModule(String id, Program program, Sandbox sandbox, Handles handles, ModuleMetadata metadata) {
        this.id = id;
        this.program = program;
        this.sandbox = sandbox;
        this.handles = handles;
        this.metadata = metadata;
    }

    public String id() {
        return id;
    }

    public Program program() {
        return program;
    }

    /** The sandbox, for what this class does not wrap: state, fuel, reload. */
    public Sandbox sandbox() {
        return sandbox;
    }

    /** The objects lent to this module as handles. */
    public Handles handles() {
        return handles;
    }

    public ModuleMetadata metadata() {
        return metadata;
    }

    /** Every exported function with an attribute, one entry per attribute, in source order. */
    public List<AttributedFunction> functions() {
        return metadata.functions();
    }

    /** The functions carrying {@code @<attribute>}: {@code functions("command")}, {@code functions("on")}. */
    public List<AttributedFunction> functions(String attribute) {
        return metadata.functions().stream().filter(f -> f.attribute().name().equals(attribute)).toList();
    }

    /** For an {@code @on(..., where = expr)} handler, whether {@code event} passes the filter; true without one. */
    public boolean accepts(AttributedFunction handler, Object event) {
        return handler.filter().isEmpty() || Boolean.TRUE.equals(invokeAs(Boolean.class, handler.filter(), event));
    }

    /** The function called {@code function}, with or without its package when that is unambiguous. */
    public Export export(String function) {
        return program.export(function);
    }

    /** Runs {@code main}; see {@link #run(String)}. */
    public int runMain() {
        return run("main");
    }

    /** Runs a function without arguments and returns 0, its int result, or the code given to {@code process.exit}. */
    public int run(String entry) {
        try {
            Object result = invoke(program.export(entry));
            return result instanceof Number n ? n.intValue() : 0;
        } catch (ExitRequest exit) {
            return exit.code();
        }
    }

    /** Calls a function by name; see {@link #invoke(Export, Object...)}. */
    public Object invoke(String function, Object... args) {
        return invoke(program.export(function), args);
    }

    /**
     * Calls a function: host objects pass as handles and come back as themselves; other values as the bindings box
     * them ({@code Long}, {@code Double}, {@code String}, {@code List}, {@code Map}, {@code Struct}, {@code Variant}).
     */
    public Object invoke(Export function, Object... args) {
        return invokeAs(Object.class, function, args);
    }

    /** Calls a function by name and converts its result to {@code target}. */
    public Object invokeAs(java.lang.reflect.Type target, String function, Object... args) {
        return invokeAs(target, program.export(function), args);
    }

    /**
     * Calls a function and converts its result to {@code target}: a class such as a record, a data class, an enum or
     * a sealed type, or a generic type from {@code new TypeRef<List<Point>>() {}.type()}.
     */
    public Object invokeAs(java.lang.reflect.Type target, Export function, Object... args) {
        Value value;
        try {
            value = sandbox.call(function, arguments(function, args));
        } catch (TrapException trap) {
            throw failure(function.name(), trap);
        }
        Type type = function.signature().result();
        if (type.kind() == Kind.VOID) {
            return null;
        }
        if (Conversions.hasHandle(type) || target == Object.class) {
            Object boxed = Conversions.toJava(value.get(), type, target, handles);
            return target == Object.class ? narrow(boxed, type) : boxed;
        }
        return value.to(target);
    }

    /** Starts a {@code suspend fn} as a task; see {@link Sandbox#spawn(Export, Object...)}. */
    public TaskOutcome spawn(Export function, Object... args) {
        try {
            return sandbox.spawn(function, arguments(function, args));
        } catch (TrapException trap) {
            throw failure(function.name(), trap);
        }
    }

    /** Resumes a task with the value it waited for; a handle passes as its id from {@code handles().idOf}. */
    public TaskOutcome resume(int task, Object value) {
        try {
            return sandbox.resume(task, value);
        } catch (TrapException trap) {
            throw failure("task " + task, trap);
        }
    }

    private Object[] arguments(Export function, Object[] args) {
        List<Type> params = function.signature().params();
        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; ++i) {
            converted[i] = i < params.size() ? Conversions.toQuartz(args[i], params.get(i), handles) : args[i];
        }
        return converted;
    }

    private RuntimeException failure(String function, TrapException trap) {
        if (trap.getCause() instanceof ExitRequest exit) {
            return exit;
        }
        return new QuartzScriptException(id, function, trap);
    }

    private static Object narrow(Object boxed, Type type) {
        if (!(boxed instanceof Number n)) {
            return boxed;
        }
        return switch (type.kind() == Kind.NULLABLE ? type.element().kind() : type.kind()) {
            case I32, U16, U8 -> n.intValue();
            case I16 -> n.shortValue();
            case I8 -> n.byteValue();
            case F32 -> n.floatValue();
            default -> boxed;
        };
    }

    @Override
    public void close() {
        sandbox.close();
    }
}
