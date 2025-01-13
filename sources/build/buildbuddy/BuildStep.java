package build.buildbuddy;

import java.io.IOException;
import java.util.SequencedMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

@FunctionalInterface
public interface BuildStep {

    String SOURCES = "sources/", RESOURCES = "resources/", CLASSES = "classes/", ARTIFACTS = "artifacts/";
    String COORDINATES = "coordinates.properties", DEPENDENCIES = "dependencies.properties";

    default boolean isAlwaysRun() {
        return false;
    }

    CompletionStage<BuildStepResult> apply(Executor executor,
                                           BuildStepContext context,
                                           SequencedMap<String, BuildStepArgument> arguments) throws IOException;
}
