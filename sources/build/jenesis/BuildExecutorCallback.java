package build.jenesis;

import module java.base;

@FunctionalInterface
public interface BuildExecutorCallback {

    BiConsumer<Boolean, Throwable> step(String identity, SequencedSet<String> keys);

    default Consumer<Throwable> module(String identity) {
        return _ -> {
        };
    }

    static BuildExecutorCallback nop() {
        return (_, _) -> (_, _) -> {
        };
    }

    static BuildExecutorCallback printing(PrintStream out, boolean verbose, Path target) {
        return new BuildExecutorCallback() {
            @Override
            public BiConsumer<Boolean, Throwable> step(String identity, SequencedSet<String> keys) {
                long started = System.nanoTime();
                if (identity == null) {
                    out.printf("[%-9s] Building in '%s'...%n", "STARTED", target);
                    return (_, throwable) -> {
                        double time = ((double) (System.nanoTime() - started) / 1_000_000) / 1_000;
                        out.printf("[%-9s] Finished in %.2f seconds%n", throwable == null ? "COMPLETED" : "FAILED", time);
                    };
                }
                return (executed, throwable) -> {
                    if (throwable != null) {
                        out.printf("[%-9s] %s: %s%n", "FAILED", identity, throwable instanceof BuildExecutorException
                                ? throwable.getCause().getMessage()
                                : throwable.getMessage());
                    } else if (executed) {
                        double time = ((double) (System.nanoTime() - started) / 1_000_000) / 1_000;
                        synchronized (out) {
                            out.printf("[%-9s] %s in %.2f seconds%n", "EXECUTED", identity, time);
                            if (verbose) {
                                Path checksums = target.resolve(identity)
                                        .resolve("checksum")
                                        .resolve("checksums");
                                if (Files.isRegularFile(checksums)) {
                                    try {
                                        HashFunction.read(checksums).forEach((file, hash) -> out.printf(
                                                "    %s  %s%n",
                                                HexFormat.of().formatHex(hash),
                                                file));
                                    } catch (IOException e) {
                                        out.printf("    Failed to list files: %s%n", e.getMessage());
                                    }
                                }
                            }
                        }
                    } else {
                        out.printf("[%-9s] %s%n", "SKIPPED", identity);
                    }
                };
            }

            @Override
            public Consumer<Throwable> module(String identity) {
                long started = System.nanoTime();
                return throwable -> {
                    if (throwable == null) {
                        double time = ((double) (System.nanoTime() - started) / 1_000_000) / 1_000;
                        out.printf("[%-9s] %s in %.2f seconds%n", "RESOLVED", identity, time);
                    }
                };
            }
        };
    }
}
