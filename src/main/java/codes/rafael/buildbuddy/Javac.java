package codes.rafael.buildbuddy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Javac implements BuildStep {

    @Override
    public CompletionStage<String> apply(Executor executor,
                                         Path previous,
                                         Path target,
                                         Map<String, BuildResult> dependencies) throws IOException {
        return null;
    }
}
