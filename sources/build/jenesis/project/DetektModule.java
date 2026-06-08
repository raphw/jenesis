package build.jenesis.project;

import module java.base;
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
import build.jenesis.step.Bind;
import build.jenesis.step.Dependencies;
import build.jenesis.step.JdkProcessBuildStep;
import build.jenesis.step.ProcessHandler;

public class DetektModule implements BuildExecutorModule {

    public static final String CHECK = "check";
    private static final String REQUIRED = "required", DEPENDENCIES = "dependencies";

    private static final String MAVEN_GROUP = "io.gitlab.arturbosch.detekt";
    private static final String MAVEN_ARTIFACT = "detekt-cli";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String group;
    private final String configFile;
    private final boolean strict;
    private final transient Function<List<String>, ? extends ProcessHandler> factory;

    public DetektModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "detekt", "detekt.yml", false, null);
    }

    private DetektModule(Map<String, Repository> repositories,
                         Map<String, Resolver> resolvers,
                         Pinning pinning,
                         String group,
                         String configFile,
                         boolean strict,
                         Function<List<String>, ? extends ProcessHandler> factory) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.group = group;
        this.configFile = configFile;
        this.strict = strict;
        this.factory = factory;
    }

    public DetektModule pinning(Pinning pinning) {
        return new DetektModule(repositories, resolvers, pinning, group, configFile, strict, factory);
    }

    public DetektModule group(String group) {
        return new DetektModule(repositories, resolvers, pinning, group, configFile, strict, factory);
    }

    public DetektModule configFile(String configFile) {
        return new DetektModule(repositories, resolvers, pinning, group, configFile, strict, factory);
    }

    public DetektModule strict(boolean strict) {
        return new DetektModule(repositories, resolvers, pinning, group, configFile, strict, factory);
    }

    public DetektModule factory(Function<List<String>, ? extends ProcessHandler> factory) {
        return new DetektModule(repositories, resolvers, pinning, group, configFile, strict, factory);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        SequencedSet<String> upstream = inherited.sequencedKeySet();
        buildExecutor.addStep(REQUIRED, new Requires(group), upstream);
        SequencedSet<String> resolveInputs = new LinkedHashSet<>();
        resolveInputs.add(REQUIRED);
        resolveInputs.addAll(upstream);
        buildExecutor.addStep(DEPENDENCIES,
                new Dependencies(repositories, resolvers).pinning(pinning),
                resolveInputs);
        SequencedSet<String> checkInputs = new LinkedHashSet<>();
        checkInputs.add(DEPENDENCIES);
        checkInputs.addAll(upstream);
        buildExecutor.addStep(CHECK,
                factory == null
                        ? new Check(group, configFile, strict)
                        : new Check(group, configFile, strict, factory),
                checkInputs);
    }

    private record Requires(String group) implements BuildStep {

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            return false;
        }

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            SequencedProperties requires = new SequencedProperties();
            requires.setProperty(group + "/runtime/maven/" + MAVEN_GROUP + "/" + MAVEN_ARTIFACT + "/RELEASE", "");
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private static class Check extends JdkProcessBuildStep {

        private final String group;
        private final String configFile;
        private final boolean strict;

        private Check(String group, String configFile, boolean strict) {
            this(group, configFile, strict, ProcessHandler.OfProcess.ofJavaHome("bin/java"));
        }

        private Check(String group,
                      String configFile,
                      boolean strict,
                      Function<List<String>, ? extends ProcessHandler> factory) {
            super("detekt", factory);
            this.group = group;
            this.configFile = configFile;
            this.strict = strict;
        }

        @Override
        public boolean acceptableExitCode(int code,
                                          Executor executor,
                                          BuildStepContext context,
                                          SequencedMap<String, BuildStepArgument> arguments) {
            return !strict || code == 0;
        }

        @Override
        public CompletionStage<List<String>> process(Executor executor,
                                                     BuildStepContext context,
                                                     SequencedMap<String, BuildStepArgument> arguments,
                                                     SequencedMap<String, SequencedMap<String, String>> properties)
                throws IOException {
            List<String> jars = new ArrayList<>(), roots = new ArrayList<>();
            boolean hasKotlin = false;
            Path config = null;
            for (BuildStepArgument argument : arguments.values()) {
                for (Path jar : Dependencies.select(argument.folder(), group, "runtime")) {
                    jars.add(jar.toString());
                }
                Path candidate = argument.folder().resolve(configFile);
                if (Files.isRegularFile(candidate)) {
                    config = candidate;
                }
                Path sources = argument.folder().resolve(Bind.SOURCES);
                if (Files.isDirectory(sources)) {
                    roots.add(sources.toString());
                    try (Stream<Path> walk = Files.walk(sources)) {
                        hasKotlin = hasKotlin || walk.anyMatch(file -> file.toString().endsWith(".kt"));
                    }
                }
            }
            if (!hasKotlin) {
                return CompletableFuture.completedStage(null);
            }
            if (jars.isEmpty()) {
                throw new IllegalStateException("No detekt jars resolved upstream of the detekt step");
            }
            if (config == null) {
                throw new IllegalStateException("No " + configFile + " found among the inputs of the detekt step");
            }
            Path report = context.next().resolve("detekt-report.xml");
            List<String> commands = new ArrayList<>(List.of(
                    "-cp", String.join(File.pathSeparator, jars),
                    "io.gitlab.arturbosch.detekt.cli.Main",
                    "--input", String.join(File.pathSeparator, roots),
                    "--config", config.toString(),
                    "--report", "xml:" + report));
            return CompletableFuture.completedStage(commands);
        }
    }
}
