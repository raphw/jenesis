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
import build.jenesis.module.ModuleInfo;
import build.jenesis.module.ModuleInfoParser;
import build.jenesis.step.Bind;

public class InternalModule implements BuildExecutorModule {

    public static final String SOURCE = "source",
            JAVA = "java",
            DELEGATE = "delegate";

    private static final String MAIN_ARTIFACTS = JAVA + "/" + JavaModule.ARTIFACTS;
    private static final String COMPILE_ARTIFACTS = DependencyScope.COMPILE.label() + "/" + DependenciesModule.ARTIFACTS;
    private static final String RUNTIME_ARTIFACTS = DependencyScope.RUNTIME.label() + "/" + DependenciesModule.ARTIFACTS;

    private final String prefix;
    private final Path source;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;

    public InternalModule(String prefix,
                          Path source,
                          Map<String, Repository> repositories,
                          Map<String, Resolver> resolvers) {
        this.prefix = prefix;
        this.source = source;
        this.repositories = repositories;
        this.resolvers = resolvers;
    }

    @Override
    public Optional<String> resolve(String path) {
        if (path.equals(DELEGATE)) {
            return Optional.of("");
        }
        if (path.startsWith(DELEGATE + "/")) {
            return Optional.of(path.substring(DELEGATE.length() + 1));
        }
        return Optional.empty();
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addSource(SOURCE, Bind.asSources(), source);
        for (DependencyScope scope : DependencyScope.values()) {
            String requiresId = scope.label() + "-requires";
            boolean compile = scope == DependencyScope.COMPILE;
            buildExecutor.addStep(requiresId, new ParseModuleInfo(prefix, compile), SOURCE);
            buildExecutor.addModule(scope.label(),
                    new DependenciesModule(repositories, resolvers, compile),
                    requiresId);
        }
        buildExecutor.addModule(JAVA, new JavaModule(), Stream.concat(
                Stream.of(SOURCE, COMPILE_ARTIFACTS),
                inherited.sequencedKeySet().stream()));
        buildExecutor.addModule(DELEGATE, (delegateExecutor, delegated) -> {
            Path mainArtifacts = delegated.get(PREVIOUS + MAIN_ARTIFACTS).resolve(BuildStep.ARTIFACTS);
            Path depArtifacts = delegated.get(PREVIOUS + RUNTIME_ARTIFACTS).resolve(BuildStep.DEPENDENCIES);
            List<URL> urls = new ArrayList<>();
            URI mainArtifact = null;
            try (DirectoryStream<Path> files = Files.newDirectoryStream(mainArtifacts)) {
                for (Path file : files) {
                    if (mainArtifact != null) {
                        throw new IllegalStateException("Expected a single main artifact in " + mainArtifacts);
                    }
                    urls.add(file.toUri().toURL());
                    mainArtifact = file.toUri();
                }
            }
            if (mainArtifact == null) {
                throw new IllegalStateException("No main artifact in " + mainArtifacts);
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(depArtifacts)) {
                for (Path file : files) {
                    urls.add(file.toUri().toURL());
                }
            }
            URI main = mainArtifact;
            URLClassLoader classLoader = new URLClassLoader(
                    urls.toArray(URL[]::new),
                    BuildExecutorModule.class.getClassLoader());
            BuildExecutorModule delegate;
            try {
                delegate = ServiceLoader
                        .load(BuildExecutorModule.class, classLoader)
                        .stream()
                        .filter(provider -> URI.create(provider.type()
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toString()).equals(main))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "No BuildExecutorModule service provider found in " + source))
                        .get();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to resolve internal build execution module " + source, e);
            }
            SequencedMap<String, Path> forwarded = new LinkedHashMap<>(delegated);
            forwarded.remove(PREVIOUS + MAIN_ARTIFACTS);
            forwarded.remove(PREVIOUS + RUNTIME_ARTIFACTS);
            delegate.accept(delegateExecutor, forwarded);
        }, Stream.concat(Stream.of(MAIN_ARTIFACTS, RUNTIME_ARTIFACTS), inherited.sequencedKeySet().stream()));
    }

    private static class ParseModuleInfo implements BuildStep {

        private final String prefix;
        private final boolean compile;
        private final ModuleInfoParser parser = new ModuleInfoParser();

        private ParseModuleInfo(String prefix, boolean compile) {
            this.prefix = prefix;
            this.compile = compile;
        }

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            return arguments.get(SOURCE).hasChanged(Path.of(BuildStep.SOURCES + "module-info.java"));
        }

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            Path moduleInfo = arguments.get(SOURCE).folder()
                    .resolve(BuildStep.SOURCES)
                    .resolve("module-info.java");
            if (!Files.isRegularFile(moduleInfo)) {
                throw new IllegalStateException(
                        "Internal module source is not modular (missing module-info.java)");
            }
            ModuleInfo info = parser.identify(moduleInfo);
            SequencedProperties properties = new SequencedProperties();
            for (String dependency : compile ? info.requires() : info.runtimeRequires()) {
                properties.setProperty(prefix + "/" + dependency, "");
            }
            properties.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
