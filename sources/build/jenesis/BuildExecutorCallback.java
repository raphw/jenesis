package build.jenesis;

import module java.base;

public interface BuildExecutorCallback {

    BiConsumer<Boolean, Throwable> step(String identity, SequencedSet<String> keys);

    static BuildExecutorCallback nop() {
        return (_, _) -> (_, _) -> {
        };
    }

    static BuildExecutorCallback printing(PrintStream out) {
        return (identity, _) -> {
            long started = System.nanoTime();
            if (identity == null) {
                out.printf("Starting build...%n");
                return (_, throwable) -> {
                    double time = ((double) (System.nanoTime() - started) / 1_000_000) / 1_000;
                    out.printf("%s build in %.2f seconds%n", throwable == null ? "COMPLETED" : "FAILED", time);
                };
            } else {
                return (executed, throwable) -> {
                    if (throwable != null) {
                        out.printf("[FAILED] %s: %s%n", identity, throwable instanceof BuildExecutorException
                                ? throwable.getCause().getMessage()
                                : throwable.getMessage());
                    } else if (executed) {
                        double time = ((double) (System.nanoTime() - started) / 1_000_000) / 1_000;
                        out.printf("[EXECUTED] %s in %.2f seconds%n", identity, time);
                    } else {
                        out.printf("[SKIPPED] %s%n", identity);
                    }
                };
            }
        };
    }
}
