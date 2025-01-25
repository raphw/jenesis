package build.buildbuddy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.function.Function;

@FunctionalInterface
public interface BuildExecutorModule {

    String PREVIOUS = "../";

    default Optional<String> resolve(String path) {
        return Optional.of(path);
    }

    void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException;

    default BuildExecutorModule resolve(Function<String, Optional<String>> resolver) {
        return new BuildExecutorModule() {
            @Override
            public Optional<String> resolve(String path) {
                return BuildExecutorModule.this.resolve(path).flatMap(resolver);
            }

            @Override
            public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
                BuildExecutorModule.this.accept(buildExecutor, inherited);
            }
        };
    }
}
