package build.buildbuddy;

import java.io.PrintStream;
import java.util.Set;
import java.util.function.BiConsumer;

public interface BuildExecutorCallback {

    BiConsumer<Boolean, Throwable> step(String identity, Set<String> arguments);

    static BuildExecutorCallback nop() {
        return (_, _) -> (_, _) -> {
        };
    }

    static BuildExecutorCallback printing(PrintStream out) {
        return (identity, _) -> (executed, throwable) -> {
            if (throwable == null) {
                out.println("[" + (executed ? "run" : "skipped" + "]") + " " + identity);
            } else {
                out.println("[failed] " + identity);
            }
        };
    }
}
