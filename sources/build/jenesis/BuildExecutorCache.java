package build.jenesis;

import module java.base;

@FunctionalInterface
public interface BuildExecutorCache {

    Optional<BuildStepResult> fetch(Executor executor,
                                    String identity,
                                    byte[] step,
                                    SequencedMap<String, Map<Path, byte[]>> inputs,
                                    Path target) throws IOException;

    default void store(Executor executor,
                       String identity,
                       byte[] step,
                       SequencedMap<String, Map<Path, byte[]>> inputs,
                       Path output) throws IOException {
    }

    default BuildExecutorCache readOnly() {
        return this::fetch;
    }

    static BuildExecutorCache nop() {
        return (_, _, _, _, _) -> Optional.empty();
    }

    static BuildExecutorCache printing(BuildExecutorCache delegate, PrintStream out) {
        return new BuildExecutorCache() {
            @Override
            public Optional<BuildStepResult> fetch(Executor executor,
                                                   String identity,
                                                   byte[] step,
                                                   SequencedMap<String, Map<Path, byte[]>> inputs,
                                                   Path target) throws IOException {
                Optional<BuildStepResult> result = delegate.fetch(executor, identity, step, inputs, target);
                if (result.isPresent()) {
                    out.printf("%s%-11s%s %s%n", BuildExecutorCallback.YELLOW, "[LOADED]", BuildExecutorCallback.RESET, identity);
                }
                return result;
            }

            @Override
            public void store(Executor executor,
                              String identity,
                              byte[] step,
                              SequencedMap<String, Map<Path, byte[]>> inputs,
                              Path output) throws IOException {
                out.printf("%s%-11s%s %s%n", BuildExecutorCallback.YELLOW, "[STORED]", BuildExecutorCallback.RESET, identity);
                delegate.store(executor, identity, step, inputs, output);
            }
        };
    }
}
