package build.jenesis.project;

import module java.base;
import build.jenesis.DependencyScope;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.module.ModuleInfo;
import build.jenesis.module.ModuleInfoParser;

public class AnnotationProcessorModule implements BuildExecutorModule {

    public static final String ARTIFACTS = "artifacts";
    private static final String REQUIRED = "required", RESOLVED = "resolved", DEPENDENCIES = "dependencies";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String qualifier;

    public AnnotationProcessorModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "annotations");
    }

    private AnnotationProcessorModule(Map<String, Repository> repositories,
                                      Map<String, Resolver> resolvers,
                                      Pinning pinning,
                                      String qualifier) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.qualifier = qualifier;
    }

    public AnnotationProcessorModule pinning(Pinning pinning) {
        return new AnnotationProcessorModule(repositories, resolvers, pinning, qualifier);
    }

    public AnnotationProcessorModule qualifier(String qualifier) {
        return new AnnotationProcessorModule(repositories, resolvers, pinning, qualifier);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        SequencedSet<String> upstream = inherited.sequencedKeySet();
        buildExecutor.addStep(REQUIRED, new Requires(qualifier), upstream);
        SequencedSet<String> resolveInputs = new LinkedHashSet<>();
        resolveInputs.add(REQUIRED);
        resolveInputs.addAll(upstream);
        buildExecutor.addModule(DEPENDENCIES,
                new DependenciesModule(repositories, resolvers, DependencyScope.RUNTIME)
                        .pinning(pinning)
                        .tag("processor:" + qualifier),
                resolveInputs);
    }

    @Override
    public Optional<String> resolve(String path) {
        if (path.equals(DEPENDENCIES + "/" + DependenciesModule.RESOLVED)) {
            return Optional.of(RESOLVED);
        }
        if (path.equals(DEPENDENCIES + "/" + DependenciesModule.ARTIFACTS)) {
            return Optional.of(ARTIFACTS);
        }
        return Optional.empty();
    }

    private record Requires(String qualifier) implements BuildStep {

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            for (BuildStepArgument argument : arguments.values()) {
                if (argument.hasChanged(Path.of(BuildStep.SOURCES + "module-info.java"))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            Path moduleInfo = null;
            for (BuildStepArgument argument : arguments.values()) {
                Path candidate = argument.folder().resolve(BuildStep.SOURCES).resolve("module-info.java");
                if (Files.isRegularFile(candidate)) {
                    moduleInfo = candidate;
                    break;
                }
            }
            SequencedProperties requires = new SequencedProperties();
            if (moduleInfo != null) {
                ModuleInfo info = new ModuleInfoParser().identify(moduleInfo);
                for (String token : info.processors()) {
                    String qualified = Resolver.qualify(token, qualifier);
                    requires.setProperty(token.startsWith("module/") ? qualified : qualified + "/RELEASE", "");
                }
            }
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
