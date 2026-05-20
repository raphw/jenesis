package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Jar;
import build.jenesis.step.Javadoc;
import build.jenesis.step.ProcessBuildStep;

public record JavaMultiProjectAssembler(boolean process, String filter) implements MultiProjectAssembler<ProjectModuleDescriptor> {

    public JavaMultiProjectAssembler() {
        this(Boolean.getBoolean("jenesis.java.process"), System.getProperty("jenesis.java.test"));
    }

    @Override
    public BuildExecutorModule apply(ProjectModuleDescriptor descriptor,
                                     Map<String, Repository> repositories,
                                     Map<String, Resolver> resolvers) {
        return (sub, outerInherited) -> {
            sub.addStep("prepare", new Prepare(), outerInherited.sequencedKeySet().stream());
            sub.addModule("java", new JavaModule(process),
                    "prepare",
                    descriptor.sources(),
                    descriptor.manifests(),
                    descriptor.resolved(DependencyScope.COMPILE),
                    descriptor.resolved(DependencyScope.RUNTIME),
                    descriptor.artifacts(DependencyScope.COMPILE),
                    descriptor.artifacts(DependencyScope.RUNTIME));
            if (descriptor.tests()) {
                Path module = outerInherited.get(descriptor.manifests()).resolve(BuildStep.MODULE);
                if (Files.isRegularFile(module)) {
                    SequencedProperties properties = SequencedProperties.ofFiles(module);
                    if (properties.getProperty("test") != null) {
                        sub.addModule("test", new TestModule(repositories, resolvers, filter)
                                        .modular(Boolean.parseBoolean(properties.getProperty("modular", "false")))
                                        .strictPinning(descriptor.strictPinning()),
                                "java",
                                "prepare",
                                descriptor.sources(),
                                descriptor.manifests(),
                                descriptor.resolved(DependencyScope.COMPILE),
                                descriptor.resolved(DependencyScope.RUNTIME),
                                descriptor.artifacts(DependencyScope.COMPILE),
                                descriptor.artifacts(DependencyScope.RUNTIME));
                    }
                }
            }
            if (descriptor.source()) {
                sub.addStep("sources", process ? Jar.process(Jar.Sort.SOURCES) : Jar.tool(Jar.Sort.SOURCES), descriptor.sources());
            }
            if (descriptor.documentation()) {
                sub.addModule("javadoc", (module, inherited) -> {
                    module.addStep("classes", process ? Javadoc.process() : Javadoc.tool(), inherited.sequencedKeySet().stream());
                    module.addStep("artifacts", process ? Jar.process(Jar.Sort.JAVADOC) : Jar.tool(Jar.Sort.JAVADOC), "classes");
                },
                descriptor.sources(),
                descriptor.manifests(),
                descriptor.resolved(DependencyScope.COMPILE),
                descriptor.resolved(DependencyScope.RUNTIME),
                descriptor.artifacts(DependencyScope.COMPILE),
                descriptor.artifacts(DependencyScope.RUNTIME));
            }
        };
    }

    private record Prepare() implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            String main = null;
            String version = null;
            for (BuildStepArgument argument : arguments.values()) {
                if (main == null) {
                    Path moduleFile = argument.folder().resolve(BuildStep.MODULE);
                    if (Files.isRegularFile(moduleFile)) {
                        SequencedProperties module = SequencedProperties.ofFiles(moduleFile);
                        String value = module.getProperty("main");
                        if (value != null && !value.isEmpty()) {
                            main = value;
                        }
                    }
                }
                if (version == null) {
                    Path metadataFile = argument.folder().resolve(BuildStep.METADATA);
                    if (Files.isRegularFile(metadataFile)) {
                        SequencedProperties metadata = SequencedProperties.ofFiles(metadataFile);
                        String value = metadata.getProperty("version");
                        if (value != null && !value.isEmpty()) {
                            version = value;
                        }
                    }
                }
            }
            Path processFolder = null;
            if (main != null) {
                processFolder = Files.createDirectories(context.next().resolve(ProcessBuildStep.PROCESS));
                SequencedProperties jar = new SequencedProperties();
                jar.setProperty("--main-class", main);
                jar.store(processFolder.resolve("jar.properties"));
            }
            if (version != null) {
                if (processFolder == null) {
                    processFolder = Files.createDirectories(context.next().resolve(ProcessBuildStep.PROCESS));
                }
                SequencedProperties javac = new SequencedProperties();
                javac.setProperty("--module-version", version);
                javac.store(processFolder.resolve("javac.properties"));
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
