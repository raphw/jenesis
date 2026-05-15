package build.jenesis;

import module java.base;

@FunctionalInterface
public interface BuildStep extends Serializable {

    String SOURCES = "sources/", RESOURCES = "resources/", CLASSES = "classes/", ARTIFACTS = "artifacts/";
    String COMPILE = "compile", RUNTIME = "runtime";
    String IDENTITY = "identity.properties", REQUIRES = "requires.properties", VERSIONS = "versions.properties", METADATA = "metadata.properties", MODULE = "module.properties";
    String COMPILE_REQUIRES = "compile-requires.properties", RUNTIME_REQUIRES = "runtime-requires.properties";
    String COMPILE_VERSIONS = "compile-versions.properties", RUNTIME_VERSIONS = "runtime-versions.properties";

    default boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(BuildStepArgument::hasChanged);
    }

    CompletionStage<BuildStepResult> apply(Executor executor,
                                           BuildStepContext context,
                                           SequencedMap<String, BuildStepArgument> arguments) throws IOException;
}
