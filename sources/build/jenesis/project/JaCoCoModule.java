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

public class JaCoCoModule implements BuildExecutorModule {

    public static final String REPORT = "report";
    private static final String REQUIRED = "required", DEPENDENCIES = "dependencies";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String tool;

    public JaCoCoModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "jacoco");
    }

    private JaCoCoModule(Map<String, Repository> repositories,
                         Map<String, Resolver> resolvers,
                         Pinning pinning,
                         String tool) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.tool = tool;
    }

    public JaCoCoModule pinning(Pinning pinning) {
        return new JaCoCoModule(repositories, resolvers, pinning, tool);
    }

    public JaCoCoModule tool(String tool) {
        return new JaCoCoModule(repositories, resolvers, pinning, tool);
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
        SequencedSet<String> reportInputs = new LinkedHashSet<>();
        reportInputs.add(DEPENDENCIES);
        reportInputs.addAll(inherited.sequencedKeySet());
        buildExecutor.addStep(REPORT, new Report(tool), reportInputs);
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
            requires.setProperty(tool + "/runtime/maven/org.jacoco/org.jacoco.cli/RELEASE", "");
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private static class Report extends JdkProcessBuildStep {

        private final String tool;

        private Report(String tool) {
            super("jacoco", ProcessHandler.OfProcess.ofJavaHome("bin/java"));
            this.tool = tool;
        }

        @Override
        public CompletionStage<List<String>> process(Executor executor,
                                                     BuildStepContext context,
                                                     SequencedMap<String, BuildStepArgument> arguments,
                                                     SequencedMap<String, SequencedMap<String, String>> properties)
                throws IOException {
            List<String> jars = new ArrayList<>(), classes = new ArrayList<>(), sources = new ArrayList<>();
            List<Path> resolved = new ArrayList<>();
            Path data = null;
            String artifact = null, version = null;
            for (BuildStepArgument argument : arguments.values()) {
                for (Path jar : Dependencies.select(argument.folder(), tool, "runtime")) {
                    jars.add(jar.toString());
                }
                Path exec = argument.folder().resolve("jacoco.exec");
                if (Files.isRegularFile(exec)) {
                    data = exec;
                }
                Path compiled = argument.folder().resolve(BuildStep.CLASSES);
                if (Files.isDirectory(compiled)) {
                    classes.add(compiled.toString());
                }
                Path source = argument.folder().resolve(BuildStep.SOURCES);
                if (Files.isDirectory(source)) {
                    sources.add(source.toString());
                }
                Path metadata = argument.folder().resolve(BuildStep.METADATA);
                if (Files.isRegularFile(metadata)) {
                    SequencedProperties descriptor = SequencedProperties.ofFiles(metadata);
                    if (artifact == null) {
                        artifact = descriptor.getProperty("artifact");
                    }
                    if (version == null) {
                        version = descriptor.getProperty("version");
                    }
                }
                Path folder = argument.folder().resolve("resolved");
                if (Files.isDirectory(folder)) {
                    try (Stream<Path> files = Files.list(folder)) {
                        files.filter(file -> file.getFileName().toString().endsWith(".jar")).forEach(resolved::add);
                    }
                }
            }
            if (artifact != null && version != null) {
                String suffix = artifact + "-" + version + ".jar";
                for (Path jar : resolved) {
                    if (jar.getFileName().toString().endsWith(suffix)) {
                        classes.add(jar.toString());
                    }
                }
            }
            if (data == null || classes.isEmpty()) {
                return CompletableFuture.completedStage(null);
            }
            if (jars.isEmpty()) {
                throw new IllegalStateException("No JaCoCo CLI jars resolved upstream of the JaCoCo report step");
            }
            Path report = Files.createDirectories(context.next().resolve(BuildStep.REPORTS + "jacoco"));
            List<String> commands = new ArrayList<>(List.of(
                    "-cp", String.join(File.pathSeparator, jars),
                    "org.jacoco.cli.internal.Main", "report", data.toString()));
            for (String directory : classes) {
                commands.add("--classfiles");
                commands.add(directory);
            }
            for (String directory : sources) {
                commands.add("--sourcefiles");
                commands.add(directory);
            }
            commands.add("--html");
            commands.add(report.toString());
            commands.add("--xml");
            commands.add(report.resolve("jacoco.xml").toString());
            return CompletableFuture.completedStage(commands);
        }
    }
}
