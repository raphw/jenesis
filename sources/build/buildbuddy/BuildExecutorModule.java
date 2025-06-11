package build.buildbuddy;

import module java.base;

@FunctionalInterface
public interface BuildExecutorModule {

    String PREVIOUS = "../";

    default Optional<String> resolve(String path) {
        return Optional.of(path);
    }

    void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException;
}
