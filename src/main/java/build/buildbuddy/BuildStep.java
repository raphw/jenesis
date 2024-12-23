package build.buildbuddy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

@FunctionalInterface
public interface BuildStep {

    default boolean isAlwaysRun() {
        return false;
    }

    CompletionStage<Boolean> apply(Executor executor,
                                   Path previous,
                                   Path target,
                                   Map<String, BuildResult> dependencies) throws IOException;
}
