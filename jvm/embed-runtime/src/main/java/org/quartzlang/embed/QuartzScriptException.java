package org.quartzlang.embed;

import java.io.Serial;
import org.quartzlang.jvm.TrapCode;
import org.quartzlang.jvm.TrapException;

/** Quartz code trapped; the message carries the Quartz trace and a host function's exception is the cause. */
public final class QuartzScriptException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String module;
    private final String function;
    private final TrapCode code;

    QuartzScriptException(String module, String function, TrapException trap) {
        super(module + ": " + function + " trapped: " + trap.getMessage(), trap.getCause() != null ? trap.getCause()
                : trap);
        this.module = module;
        this.function = function;
        this.code = trap.code();
    }

    public String module() {
        return module;
    }

    public String function() {
        return function;
    }

    public TrapCode code() {
        return code;
    }
}
