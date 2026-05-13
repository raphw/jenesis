package build.jenesis;

import module java.base;

@FunctionalInterface
public interface BuildStep extends Serializable {

    String SOURCES = "sources/", RESOURCES = "resources/", CLASSES = "classes/", ARTIFACTS = "artifacts/";
    String IDENTITY = "identity.properties", REQUIRES = "requires.properties", VERSIONS = "versions.properties", METADATA = "metadata.properties";

    default boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(BuildStepArgument::hasChanged);
    }

    CompletionStage<BuildStepResult> apply(Executor executor,
                                           BuildStepContext context,
                                           SequencedMap<String, BuildStepArgument> arguments) throws IOException;
}
