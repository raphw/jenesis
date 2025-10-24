package build.jenesis;

import module java.base;

@FunctionalInterface
public interface BuildStep {

    String SOURCES = "sources/", RESOURCES = "resources/", CLASSES = "classes/", ARTIFACTS = "artifacts/";
    String COORDINATES = "coordinates.properties", DEPENDENCIES = "dependencies.properties";

    default boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(BuildStepArgument::hasChanged);
    }

    CompletionStage<BuildStepResult> apply(Executor executor,
                                           BuildStepContext context,
                                           SequencedMap<String, BuildStepArgument> arguments) throws IOException;
}
