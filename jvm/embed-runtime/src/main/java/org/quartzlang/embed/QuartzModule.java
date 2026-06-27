package org.quartzlang.embed;

import java.util.ArrayList;
import java.util.List;
import org.quartzlang.jvm.Export;
import org.quartzlang.jvm.Kind;
import org.quartzlang.jvm.Program;
import org.quartzlang.jvm.Sandbox;
import org.quartzlang.jvm.TrapException;
import org.quartzlang.jvm.Type;
import org.quartzlang.jvm.Value;

/** One packaged module running in its own sandbox, with its kits and host functions bound. One thread at a time. */
public final class QuartzModule implements AutoCloseable {
    private final String id;
    private final Program program;
    private final Sandbox sandbox;
    private final Handles handles;

    QuartzModule(String id, Program program, Sandbox sandbox, Handles handles) {
        this.id = id;
        this.program = program;
        this.sandbox = sandbox;
        this.handles = handles;
    }

    public String id() {
        return id;
    }

    public Program program() {
        return program;
    }

    /** The sandbox, for what this class does not wrap: state, tasks, fuel. */
    public Sandbox sandbox() {
        return sandbox;
    }

    /** The objects lent to this module as handles. */
    public Handles handles() {
        return handles;
    }

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

    /** Calls a function: host objects pass as handles, the result comes back as the Java type the facade declares. */
    public Object invoke(Export function, Object... args) {
        List<Type> params = function.signature().params();
        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; ++i) {
            converted[i] = i < params.size() ? Conversions.toQuartz(args[i], params.get(i), handles) : args[i];
        }
        Value value;
        try {
            value = sandbox.call(function, converted);
        } catch (TrapException trap) {
            if (trap.getCause() instanceof ExitRequest exit) {
                throw exit;
            }
            throw new QuartzScriptException(id, function.name(), trap);
        }
        Type type = function.signature().result();
        return type.kind() == Kind.VOID ? null : fromQuartz(value.get(), type);
    }

    private Object fromQuartz(Object boxed, Type type) {
        if (boxed == null) {
            return null;
        }
        return switch (type.kind()) {
            case NULLABLE -> fromQuartz(boxed, type.element());
            case HANDLE -> handles.get(((Number) boxed).longValue());
            case I32, U16 -> ((Number) boxed).intValue();
            case I16, U8 -> type.kind() == Kind.U8 ? ((Number) boxed).intValue() : ((Number) boxed).shortValue();
            case I8 -> ((Number) boxed).byteValue();
            case F32 -> ((Number) boxed).floatValue();
            case LIST -> {
                List<Object> out = new ArrayList<>();
                for (Object e : (List<?>) boxed) {
                    out.add(fromQuartz(e, type.element()));
                }
                yield out;
            }
            default -> boxed;
        };
    }

    @Override
    public void close() {
        sandbox.close();
    }
}
