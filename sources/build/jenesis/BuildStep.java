package build.jenesis;

import module java.base;

@FunctionalInterface
public interface BuildStep extends Serializable {

    String SOURCES = "sources/",
            RESOURCES = "resources/",
            CLASSES = "classes/",
            ARTIFACTS = "artifacts/",
            DOCUMENTATION = "documentation/",
            DEPENDENCIES = "dependencies/";

    String IDENTITY = "identity.properties",
            REQUIRES = "requires.properties",
            VERSIONS = "versions.properties",
            MODULE = "module.properties",
            METADATA = "metadata.properties",
            SCOPES = "scopes.properties",
            EXCLUSIONS = "exclusions.properties";

    default BuildExecutorModule asModule(String name) {
        return new BuildExecutorModule() {
            @Override
            public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
                buildExecutor.addStep(name, BuildStep.this);
            }

            @Override
            public Optional<String> resolve(String path) {
                return Optional.of(path.substring(name.length() + 1));
            }
        };
    }

    default boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(BuildStepArgument::hasChanged);
    }

    CompletionStage<BuildStepResult> apply(Executor executor,
                                           BuildStepContext context,
                                           SequencedMap<String, BuildStepArgument> arguments) throws IOException;

    static void linkOrCopy(Path link, Path existing) throws IOException {
        try {
            Files.createLink(link, existing);
        } catch (UnsupportedOperationException | FileSystemException _) {
            Files.copy(existing, link);
        }
    }
}
