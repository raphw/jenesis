package codes.rafael.buildbuddy;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Javac implements BuildStep {

    @Override
    public CompletionStage<Path> apply(Executor executor, Map<String, BuildResult> dependencies) {
        return null;
    }
}
