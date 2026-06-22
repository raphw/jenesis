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

    default boolean stores() {
        return false;
    }

    default void touch(Executor executor,
                       String identity,
                       byte[] step,
                       SequencedMap<String, Map<Path, byte[]>> inputs) throws IOException {
    }

    static BuildExecutorCache nop() {
        return (_, _, _, _, _) -> Optional.empty();
    }
}
