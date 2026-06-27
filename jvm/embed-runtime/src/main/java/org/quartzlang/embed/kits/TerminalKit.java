package org.quartzlang.embed.kits;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.quartzlang.embed.Kit;
import org.quartzlang.embed.KitContext;

/** {@code org.quartzlang.embed.terminal}: standard output, error and input. */
public final class TerminalKit implements Kit {
    private static final String P = "org.quartzlang.embed.terminal.";
    private static final class Input {
        static final BufferedReader READER = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    @Override
    public String name() {
        return "terminal";
    }

    @Override
    public void bind(KitContext k) {
        k.bind(P + "print", c -> {
            System.out.print(c.stringArg(0));
            System.out.flush();
            return null;
        });
        k.bind(P + "println", c -> {
            System.out.println(c.stringArg(0));
            return null;
        });
        k.bind(P + "eprintln", c -> {
            System.err.println(c.stringArg(0));
            return null;
        });
        k.bind(P + "readLine", c -> Input.READER.readLine());
        k.bind(P + "interactive", c -> {
            Console console = System.console();
            return console != null && console.isTerminal();
        });
        k.bind(P + "width", c -> {
            String columns = System.getenv("COLUMNS");
            try {
                return columns == null ? 80L : Math.max(1L, Long.parseLong(columns.strip()));
            } catch (NumberFormatException e) {
                return 80L;
            }
        });
    }
}
