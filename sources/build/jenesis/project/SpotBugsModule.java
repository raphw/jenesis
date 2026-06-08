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
import build.jenesis.step.Dependencies;
import build.jenesis.step.JdkProcessBuildStep;
import build.jenesis.step.ProcessHandler;

public class SpotBugsModule implements BuildExecutorModule {

    public static final String CHECK = "check";
    private static final String REQUIRED = "required", DEPENDENCIES = "dependencies";

    private static final String MAVEN_GROUP = "com.github.spotbugs";
    private static final String MAVEN_ARTIFACT = "spotbugs";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String group;
    private final String configFile;
    private final boolean strict;
    private final transient Function<List<String>, ? extends ProcessHandler> factory;

    public SpotBugsModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "spotbugs", "spotbugs-exclude.xml", false, null);
    }

    private SpotBugsModule(Map<String, Repository> repositories,
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

    public SpotBugsModule pinning(Pinning pinning) {
        return new SpotBugsModule(repositories, resolvers, pinning, group, configFile, strict, factory);
    }

    public SpotBugsModule group(String group) {
        return new SpotBugsModule(repositories, resolvers, pinning, group, configFile, strict, factory);
    }

    public SpotBugsModule configFile(String configFile) {
        return new SpotBugsModule(repositories, resolvers, pinning, group, configFile, strict, factory);
    }

    public SpotBugsModule strict(boolean strict) {
        return new SpotBugsModule(repositories, resolvers, pinning, group, configFile, strict, factory);
    }

    public SpotBugsModule factory(Function<List<String>, ? extends ProcessHandler> factory) {
        return new SpotBugsModule(repositories, resolvers, pinning, group, configFile, strict, factory);
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
            super("spotbugs", factory);
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
            List<String> jars = new ArrayList<>(), classes = new ArrayList<>(), auxiliary = new ArrayList<>();
            Path config = null;
            for (BuildStepArgument argument : arguments.values()) {
                for (Path jar : Dependencies.select(argument.folder(), group, "runtime")) {
                    jars.add(jar.toString());
                }
                for (Path jar : Dependencies.select(argument.folder(), "compile")) {
                    auxiliary.add(jar.toString());
                }
                Path candidate = argument.folder().resolve(configFile);
                if (Files.isRegularFile(candidate)) {
                    config = candidate;
                }
                Path compiled = argument.folder().resolve(BuildStep.CLASSES);
                if (Files.isDirectory(compiled)) {
                    classes.add(compiled.toString());
                }
            }
            if (classes.isEmpty()) {
                return CompletableFuture.completedStage(null);
            }
            if (jars.isEmpty()) {
                throw new IllegalStateException("No SpotBugs jars resolved upstream of the SpotBugs step");
            }
            Path report = context.next().resolve("spotbugs-report.xml");
            List<String> commands = new ArrayList<>(List.of(
                    "-cp", String.join(File.pathSeparator, jars),
                    "edu.umd.cs.findbugs.LaunchAppropriateUI", "-textui",
                    "-xml", "-output", report.toString()));
            if (config != null) {
                commands.add("-exclude");
                commands.add(config.toString());
            }
            if (!auxiliary.isEmpty()) {
                commands.add("-auxclasspath");
                commands.add(String.join(File.pathSeparator, auxiliary));
            }
            commands.addAll(classes);
            return CompletableFuture.completedStage(commands);
        }
    }
}
