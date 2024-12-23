package codes.rafael.buildbuddy;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

@FunctionalInterface
public interface BuildStep {

    CompletionStage<Path> apply(Executor executor,
                                Path previous,
                                Path target,
                                Map<String, BuildResult> dependencies);
}
