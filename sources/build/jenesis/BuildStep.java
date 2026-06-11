package build.jenesis;

import module java.base;

@FunctionalInterface
public interface BuildStep extends Serializable {

    String SOURCES = "sources/",
            RESOURCES = "resources/",
            CLASSES = "classes/",
            ARTIFACTS = "artifacts/",
            DOCUMENTATION = "documentation/",
            REPORTS = "reports/";

    String IDENTITY = "identity.properties",
            REQUIRES = "requires.properties",
            VERSIONS = "versions.properties",
            MODULE = "module.properties",
            METADATA = "metadata.properties",
            EXCLUSIONS = "exclusions.properties",
            DEPENDENCIES = "dependencies.properties";

    default BuildExecutorModule asModule(String name) {
        return new BuildExecutorModule() {
            @Override
            public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
                buildExecutor.addStep(name, BuildStep.this, inherited.sequencedKeySet());
            }

            @Override
            public Optional<String> resolve(String path) {
                return path.equals(name) ? Optional.of("") : Optional.empty();
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

    static boolean underMetaInfVersions(Path path) {
        return path.getNameCount() >= 2
                && path.getName(0).toString().equals("META-INF")
                && path.getName(1).toString().equals("versions");
    }
}
