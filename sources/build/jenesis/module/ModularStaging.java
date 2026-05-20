package build.jenesis.module;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class ModularStaging implements BuildStep {

    private final boolean includeTestModules;

    public ModularStaging() {
        this(false);
    }

    public ModularStaging(boolean includeTestModules) {
        this.includeTestModules = includeTestModules;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        for (BuildStepArgument argument : arguments.values()) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(argument.folder())) {
                for (Path moduleDir : stream) {
                    stage(moduleDir, context.next());
                }
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private void stage(Path moduleDir, Path next) throws IOException {
        if (!Files.isDirectory(moduleDir)) {
            return;
        }
        Path moduleFile = moduleDir.resolve(BuildStep.MODULE);
        if (!Files.isRegularFile(moduleFile)) {
            return;
        }
        SequencedProperties module = SequencedProperties.ofFiles(moduleFile);
        if (!includeTestModules && module.getProperty("tests") != null) {
            return;
        }
        String moduleName = module.getProperty("module");
        if (moduleName == null) {
            throw new IllegalStateException("Missing 'module' property in module.properties for " + moduleDir);
        }
        Path metadataFile = moduleDir.resolve(BuildStep.METADATA);
        String version = Files.isRegularFile(metadataFile)
                ? SequencedProperties.ofFiles(metadataFile).getProperty("version")
                : null;
        Path target = version == null
                ? next.resolve(moduleName)
                : next.resolve(moduleName).resolve(version);
        Files.createDirectories(target);
        Files.createLink(target.resolve(moduleName + ".jar"), moduleDir.resolve("classes.jar"));
        Files.createLink(target.resolve(moduleName + "-sources.jar"), moduleDir.resolve("sources.jar"));
        Files.createLink(target.resolve(moduleName + "-javadoc.jar"), moduleDir.resolve("javadoc.jar"));
    }
}
