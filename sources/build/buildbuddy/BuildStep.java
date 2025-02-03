package build.buildbuddy;

import java.io.IOException;
import java.util.SequencedMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

@FunctionalInterface
public interface BuildStep {

    String SOURCES = "sources/", RESOURCES = "resources/", CLASSES = "classes/", ARTIFACTS = "artifacts/";
    String COORDINATES = "coordinates.properties", DEPENDENCIES = "dependencies.properties", URIS = "uris.properties";

    default boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(BuildStepArgument::hasChanged);
    }

    CompletionStage<BuildStepResult> apply(Executor executor,
                                           BuildStepContext context,
                                           SequencedMap<String, BuildStepArgument> arguments) throws IOException;
}
