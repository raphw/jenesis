package build.buildbuddy;

import java.io.PrintStream;
import java.util.SequencedSet;
import java.util.function.BiConsumer;

public interface BuildExecutorCallback {

    BiConsumer<Boolean, Throwable> step(String identity, SequencedSet<String> arguments);

    static BuildExecutorCallback nop() {
        return (_, _) -> (_, _) -> {
        };
    }

    static BuildExecutorCallback printing(PrintStream out) {
        return (identity, _) -> {
            long started = System.nanoTime();
            return (executed, throwable) -> {
                if (throwable != null) {
                    out.printf("[FAILED] %s: %s%n", identity, throwable instanceof BuildExecutorException
                            ? throwable.getCause().getMessage()
                            : throwable.getMessage());
                } else if (executed) {
                    double time = ((double) (System.nanoTime() - started) / 1_000_000) / 1_000;
                    out.printf("[EXECUTED] %s in %.2f%n", identity, time);
                } else {
                    out.printf("[SKIPPED] %s%n", identity);
                }
            };
        };
    }
}
