package org.quartzlang.embed;

import java.io.Serial;

/** Thrown by {@code process.exit}; the running call stops and {@link QuartzModule#runMain()} returns the code. */
public final class ExitRequest extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final int code;

    public ExitRequest(int code) {
        super("exit " + code, null, false, false);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
