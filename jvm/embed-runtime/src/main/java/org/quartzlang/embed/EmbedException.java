package org.quartzlang.embed;

import java.io.Serial;

/** A module cannot be loaded or set up: missing module, kit not granted, host method gone. */
public class EmbedException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public EmbedException(String message) {
        super(message);
    }

    public EmbedException(String message, Throwable cause) {
        super(message, cause);
    }
}
