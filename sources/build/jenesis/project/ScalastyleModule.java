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

public class ScalastyleModule implements BuildExecutorModule {

    public static final String CHECK = "check";
    private static final String REQUIRED = "required", DEPENDENCIES = "dependencies";

    private static final String MAVEN_GROUP = "com.beautiful-scala";
    private static final String MAVEN_ARTIFACT = "scalastyle_2.13";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String tool;
    private final String configFile;
    private final boolean strict;

    public ScalastyleModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "scalastyle", "scalastyle-config.xml", false);
    }

    private ScalastyleModule(Map<String, Repository> repositories,
                             Map<String, Resolver> resolvers,
                             Pinning pinning,
                             String tool,
                             String configFile,
                             boolean strict) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.tool = tool;
        this.configFile = configFile;
        this.strict = strict;
    }

    public static Path configurationFile(Path configuration) {
        Path file = configuration.resolve("scalastyle-config.xml");
        return Files.isRegularFile(file) ? file : null;
    }

    public ScalastyleModule pinning(Pinning pinning) {
        return new ScalastyleModule(repositories, resolvers, pinning, tool, configFile, strict);
    }

    public ScalastyleModule tool(String tool) {
        return new ScalastyleModule(repositories, resolvers, pinning, tool, configFile, strict);
    }

    public ScalastyleModule configFile(String configFile) {
        return new ScalastyleModule(repositories, resolvers, pinning, tool, configFile, strict);
    }

    public ScalastyleModule strict(boolean strict) {
        return new ScalastyleModule(repositories, resolvers, pinning, tool, configFile, strict);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(REQUIRED, new Requires(tool), inherited.sequencedKeySet());
        SequencedSet<String> resolveInputs = new LinkedHashSet<>();
        resolveInputs.add(REQUIRED);
        resolveInputs.addAll(inherited.sequencedKeySet());
        buildExecutor.addStep(DEPENDENCIES,
                new Dependencies(repositories, resolvers).pinning(pinning),
                resolveInputs);
        SequencedSet<String> checkInputs = new LinkedHashSet<>();
        checkInputs.add(DEPENDENCIES);
        checkInputs.addAll(inherited.sequencedKeySet());
        buildExecutor.addStep(CHECK, new Check(tool, configFile, strict), checkInputs);
    }

    private record Requires(String tool) implements BuildStep {

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
            requires.setProperty(tool + "/runtime/maven/" + MAVEN_GROUP + "/" + MAVEN_ARTIFACT + "/RELEASE", "");
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private static class Check extends JdkProcessBuildStep {

        private final String tool;
        private final String configFile;
        private final boolean strict;

        private Check(String tool, String configFile, boolean strict) {
            super("scalastyle", ProcessHandler.OfProcess.ofJavaHome("bin/java"));
            this.tool = tool;
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
            boolean hasScala = false;
            Path config = null;
            for (BuildStepArgument argument : arguments.values()) {
                for (Path jar : Dependencies.select(argument.folder(), tool, "runtime")) {
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
                        hasScala = hasScala || walk.anyMatch(file -> file.toString().endsWith(".scala"));
                    }
                }
            }
            if (!hasScala) {
                return CompletableFuture.completedStage(null);
            }
            if (jars.isEmpty()) {
                throw new IllegalStateException("No Scalastyle jars resolved upstream of the Scalastyle step");
            }
            if (config == null) {
                throw new IllegalStateException("No " + configFile + " found among the inputs of the Scalastyle step");
            }
            Path report = Files.createDirectories(context.next().resolve(BuildStep.REPORTS + "scalastyle")).resolve("scalastyle-report.xml");
            List<String> commands = new ArrayList<>(List.of(
                    "-cp", String.join(File.pathSeparator, jars),
                    "org.scalastyle.Main",
                    "--config", config.toString(),
                    "--xmlOutput", report.toString()));
            commands.addAll(roots);
            return CompletableFuture.completedStage(commands);
        }
    }
}
