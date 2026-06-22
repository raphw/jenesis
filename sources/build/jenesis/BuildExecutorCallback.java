package build.jenesis;

import module java.base;

@FunctionalInterface
public interface BuildExecutorCallback {

    String RESET = "\033[0m";
    String RED = "\033[31m";
    String GREEN = "\033[32m";
    String YELLOW = "\033[33m";
    String BLUE = "\033[34m";
    String CYAN = "\033[36m";

    BiConsumer<Boolean, Throwable> step(String identity, SequencedSet<String> keys);

    default Consumer<Throwable> module(String identity) {
        return _ -> {
        };
    }

    default void loaded(String identity, long duration) {
    }

    default void stored(String identity, long duration) {
    }

    static BuildExecutorCallback nop() {
        return (_, _) -> (_, _) -> {
        };
    }

    static BuildExecutorCallback printing(PrintStream out, boolean verbose, boolean cache, Path target) {
        return new BuildExecutorCallback() {
            @Override
            public BiConsumer<Boolean, Throwable> step(String identity, SequencedSet<String> keys) {
                long started = System.nanoTime();
                if (identity == null) {
                    out.printf("%s%-11s%s Building in '%s'...\n", GREEN, "[STARTED]", RESET, target);
                    return (_, throwable) -> {
                        double time = ((double) (System.nanoTime() - started) / 1_000_000) / 1_000;
                        out.printf("%s%-11s%s Finished %sin %.2f seconds%s\n",
                                throwable == null ? GREEN : RED,
                                throwable == null ? "[COMPLETED]" : "[FAILED]",
                                RESET,
                                CYAN,
                                time,
                                RESET);
                    };
                }
                return (executed, throwable) -> {
                    if (throwable != null) {
                        out.printf("%s%-11s%s %s: %s\n", RED, "[FAILED]", RESET, identity, throwable instanceof BuildExecutorException
                                ? throwable.getCause().getMessage()
                                : throwable.getMessage());
                    } else if (executed) {
                        double time = ((double) (System.nanoTime() - started) / 1_000_000) / 1_000;
                        synchronized (out) {
                            out.printf("%s%-11s%s %s %sin %.2f seconds%s\n",
                                    GREEN, "[EXECUTED]", RESET, identity, CYAN, time, RESET);
                            if (verbose) {
                                Path checksums = target.resolve(identity)
                                        .resolve("checksum")
                                        .resolve("output.properties");
                                if (Files.isRegularFile(checksums)) {
                                    try {
                                        HashFunction.read(checksums).forEach((file, hash) -> out.printf(
                                                "            %s  %s\n",
                                                HexFormat.of().formatHex(hash),
                                                file));
                                    } catch (IOException e) {
                                        out.printf("            Failed to list files: %s\n", e.getMessage());
                                    }
                                }
                            }
                        }
                    } else {
                        out.printf("%s%-11s%s %s\n", BLUE, "[SKIPPED]", RESET, identity);
                    }
                };
            }

            @Override
            public Consumer<Throwable> module(String identity) {
                long started = System.nanoTime();
                return throwable -> {
                    if (throwable == null) {
                        double time = ((double) (System.nanoTime() - started) / 1_000_000) / 1_000;
                        out.printf("%s%-11s%s %s %sin %.2f seconds%s\n",
                                GREEN, "[RESOLVED]", RESET, identity, CYAN, time, RESET);
                    }
                };
            }

            @Override
            public void loaded(String identity, long duration) {
                if (cache) {
                    out.printf("%s%-11s%s %s %sin %.2f seconds%s\n",
                            YELLOW, "[LOADED]", RESET, identity, CYAN, ((double) duration / 1_000_000) / 1_000, RESET);
                }
            }

            @Override
            public void stored(String identity, long duration) {
                if (cache) {
                    out.printf("%s%-11s%s %s %sin %.2f seconds%s\n",
                            YELLOW, "[STORED]", RESET, identity, CYAN, ((double) duration / 1_000_000) / 1_000, RESET);
                }
            }
        };
    }
}
