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

public class JavaMultiProjectAssembler implements MultiProjectAssembler<ProjectModuleDescriptor> {

    @Override
    public BuildExecutorModule apply(ProjectModuleDescriptor descriptor,
                                     Map<String, Repository> repositories,
                                     Map<String, Resolver> resolvers) {
        return (sub, outerInherited) -> {
            BuildExecutorModule java;
            if (descriptor.tests()) {
                Properties module = new Properties();
                Path moduleFile = outerInherited.get(descriptor.manifests()).resolve(BuildStep.MODULE);
                if (Files.isRegularFile(moduleFile)) {
                    try (Reader reader = Files.newBufferedReader(moduleFile)) {
                        module.load(reader);
                    }
                }
                boolean test = module.getProperty("tests") != null;
                java = new JavaModule().test(test, null, repositories, resolvers);
            } else {
                java = new JavaModule();
            }
            sub.addStep("prepare", new Prepare(), descriptor.manifests());
            sub.addModule("java", java,
                    "prepare",
                    descriptor.sources(),
                    descriptor.manifests(),
                    descriptor.resolved(DependencyScope.COMPILE),
                    descriptor.resolved(DependencyScope.RUNTIME),
                    descriptor.artifacts(DependencyScope.COMPILE),
                    descriptor.artifacts(DependencyScope.RUNTIME));
            if (descriptor.source()) {
                sub.addStep("sources", Jar.tool(Jar.Sort.SOURCES), descriptor.sources());
            }
            if (descriptor.javadoc()) {
                sub.addModule("javadoc", (module, inherited) -> {
                    module.addStep("classes", Javadoc.tool(), inherited.sequencedKeySet().stream());
                    module.addStep("artifacts", Jar.tool(Jar.Sort.JAVADOC), "classes");
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
            for (BuildStepArgument argument : arguments.values()) {
                Path moduleFile = argument.folder().resolve(BuildStep.MODULE);
                if (!Files.isRegularFile(moduleFile)) {
                    continue;
                }
                Properties module = new SequencedProperties();
                try (Reader reader = Files.newBufferedReader(moduleFile)) {
                    module.load(reader);
                }
                String value = module.getProperty("main");
                if (value != null && !value.isEmpty()) {
                    main = value;
                    break;
                }
            }
            if (main != null) {
                Path target = Files.createDirectories(context.next().resolve(ProcessBuildStep.PROCESS));
                Properties jar = new SequencedProperties();
                jar.setProperty("--main-class", main);
                try (BufferedWriter writer = Files.newBufferedWriter(target.resolve("jar.properties"))) {
                    jar.store(writer, null);
                }
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
