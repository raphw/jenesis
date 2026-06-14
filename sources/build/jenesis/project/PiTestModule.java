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

public class PiTestModule implements BuildExecutorModule {

    public static final String MUTATE = "mutate";
    private static final String REQUIRED = "required", DEPENDENCIES = "dependencies";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String group;
    private final Path configuration;

    public PiTestModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "pitest", null);
    }

    private PiTestModule(Map<String, Repository> repositories,
                         Map<String, Resolver> resolvers,
                         Pinning pinning,
                         String group,
                         Path configuration) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.group = group;
        this.configuration = configuration;
    }

    public static Path configurationFile(Path configuration) {
        Path file = configuration.resolve("pitest.properties");
        return Files.isRegularFile(file) ? file : null;
    }

    public PiTestModule pinning(Pinning pinning) {
        return new PiTestModule(repositories, resolvers, pinning, group, configuration);
    }

    public PiTestModule group(String group) {
        return new PiTestModule(repositories, resolvers, pinning, group, configuration);
    }

    public PiTestModule configuration(Path configuration) {
        return new PiTestModule(repositories, resolvers, pinning, group, configuration);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(REQUIRED, new Requires(group), inherited.sequencedKeySet());
        SequencedSet<String> resolveInputs = new LinkedHashSet<>();
        resolveInputs.add(REQUIRED);
        resolveInputs.addAll(inherited.sequencedKeySet());
        buildExecutor.addStep(DEPENDENCIES,
                new Dependencies(repositories, resolvers).pinning(pinning),
                resolveInputs);
        SequencedSet<String> mutateInputs = new LinkedHashSet<>();
        mutateInputs.add(DEPENDENCIES);
        mutateInputs.addAll(inherited.sequencedKeySet());
        buildExecutor.addStep(MUTATE, new Mutate(group, configuration), mutateInputs);
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
            String launcher = junitPlatformVersion(arguments);
            SequencedProperties requires = new SequencedProperties();
            requires.setProperty(group + "/runtime/maven/org.pitest/pitest-command-line/RELEASE", "");
            requires.setProperty(group + "/runtime/maven/org.pitest/pitest-junit5-plugin/RELEASE", "");
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            if (launcher != null) {
                SequencedProperties versions = new SequencedProperties();
                versions.setProperty(group + "/maven/org.junit.platform/junit-platform-launcher", launcher);
                versions.store(context.next().resolve(BuildStep.VERSIONS));
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }

        private static String junitPlatformVersion(SequencedMap<String, BuildStepArgument> arguments) throws IOException {
            for (BuildStepArgument argument : arguments.values()) {
                Path versions = argument.folder().resolve(BuildStep.VERSIONS);
                if (!Files.isRegularFile(versions)) {
                    continue;
                }
                SequencedProperties properties = SequencedProperties.ofFiles(versions);
                for (String key : properties.stringPropertyNames()) {
                    if (key.endsWith("/org.junit.platform/junit-platform-engine")
                            || key.endsWith("/org.junit.platform/junit-platform-commons")) {
                        String value = properties.getProperty(key);
                        int space = value.indexOf(' ');
                        return space < 0 ? value : value.substring(0, space);
                    }
                }
            }
            return null;
        }
    }

    private static class Mutate extends JdkProcessBuildStep {

        private final String group;
        private final Path configuration;

        private Mutate(String group, Path configuration) {
            super("pitest", ProcessHandler.OfProcess.ofJavaHome("bin/java"));
            this.group = group;
            this.configuration = configuration;
        }

        @Override
        public CompletionStage<List<String>> process(Executor executor,
                                                     BuildStepContext context,
                                                     SequencedMap<String, BuildStepArgument> arguments,
                                                     SequencedMap<String, SequencedMap<String, String>> properties)
                throws IOException {
            SequencedProperties config = new SequencedProperties();
            if (configuration != null && Files.isRegularFile(configuration)) {
                config.putAll(SequencedProperties.ofFiles(configuration));
            }
            List<String> tools = new ArrayList<>(), application = new ArrayList<>(), sources = new ArrayList<>();
            for (BuildStepArgument argument : arguments.values()) {
                for (Path jar : Dependencies.select(argument.folder(), group, "runtime")) {
                    tools.add(jar.toString());
                }
                for (Path jar : Dependencies.select(argument.folder(), "runtime")) {
                    application.add(jar.toString());
                }
                Path classes = argument.folder().resolve(BuildStep.CLASSES);
                if (Files.isDirectory(classes)) {
                    application.add(classes.toString());
                }
                Path source = argument.folder().resolve(BuildStep.SOURCES);
                if (Files.isDirectory(source)) {
                    sources.add(source.toString());
                }
                Path candidate = argument.folder().resolve("pitest.properties");
                if (Files.isRegularFile(candidate)) {
                    config.putAll(SequencedProperties.ofFiles(candidate));
                }
            }
            if (tools.isEmpty()) {
                throw new IllegalStateException("No PIT jars resolved upstream of the PIT mutation step");
            }
            if (application.isEmpty() || sources.isEmpty()) {
                return CompletableFuture.completedStage(null);
            }
            Path report = Files.createDirectories(context.next().resolve(BuildStep.REPORTS + "pitest"));
            List<String> classPath = new ArrayList<>(new LinkedHashSet<>(application));
            List<String> commands = new ArrayList<>(List.of(
                    "-cp", Stream.concat(tools.stream(), classPath.stream()).distinct().collect(Collectors.joining(File.pathSeparator)),
                    "org.pitest.mutationtest.commandline.MutationCoverageReport",
                    "--reportDir", report.toString(),
                    "--classPath", String.join(",", classPath),
                    "--sourceDirs", String.join(",", sources),
                    "--targetClasses", config.getProperty("targetClasses", "*"),
                    "--targetTests", config.getProperty("targetTests", "*"),
                    "--outputFormats", config.getProperty("outputFormats", "XML"),
                    "--timestampedReports", "false",
                    "--failWhenNoMutations", config.getProperty("failWhenNoMutations", "false")));
            String mutators = config.getProperty("mutators");
            if (mutators != null) {
                commands.add("--mutators");
                commands.add(mutators);
            }
            return CompletableFuture.completedStage(commands);
        }
    }
}
