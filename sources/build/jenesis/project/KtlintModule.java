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
import build.jenesis.step.ProcessBuildStep;
import build.jenesis.step.ProcessHandler;

public class KtlintModule implements BuildExecutorModule {

    public static final String CHECK = "check";
    private static final String REQUIRED = "required", DEPENDENCIES = "dependencies";

    private static final String MAVEN_GROUP = "com.pinterest.ktlint";
    private static final String MAVEN_ARTIFACT = "ktlint-cli";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String tool;
    private final boolean strict;
    private final Boolean printing;

    public KtlintModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "ktlint", false, null);
    }

    private KtlintModule(Map<String, Repository> repositories,
                         Map<String, Resolver> resolvers,
                         Pinning pinning,
                         String tool,
                         boolean strict,
                         Boolean printing) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.tool = tool;
        this.strict = strict;
        this.printing = printing;
    }

    public static Path configurationFile(SequencedSet<Path> configuration) {
        return BuildStep.locate(configuration, ".editorconfig");
    }

    public KtlintModule pinning(Pinning pinning) {
        return new KtlintModule(repositories, resolvers, pinning, tool, strict, printing);
    }

    public KtlintModule tool(String tool) {
        return new KtlintModule(repositories, resolvers, pinning, tool, strict, printing);
    }

    public KtlintModule strict(boolean strict) {
        return new KtlintModule(repositories, resolvers, pinning, tool, strict, printing);
    }

    public KtlintModule printing(boolean printing) {
        return new KtlintModule(repositories, resolvers, pinning, tool, strict, printing);
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
        buildExecutor.addStep(CHECK, new Check(tool, strict, printing), checkInputs);
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
        private final boolean strict;

        private Check(String tool, boolean strict, Boolean printing) {
            super("ktlint", ProcessHandler.OfProcess.ofJavaHome("bin/java"), printing == null ? ProcessBuildStep.printing("ktlint") : printing);
            this.tool = tool;
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
            List<String> jars = new ArrayList<>(), files = new ArrayList<>();
            for (BuildStepArgument argument : arguments.values()) {
                for (Path jar : Dependencies.select(argument.folder(), tool, "runtime")) {
                    jars.add(jar.toString());
                }
                Path sources = argument.folder().resolve(Bind.SOURCES);
                if (Files.isDirectory(sources)) {
                    Files.walkFileTree(sources, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (file.toString().endsWith(".kt")) {
                                files.add(file.toString());
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
            if (files.isEmpty()) {
                return CompletableFuture.completedStage(null);
            }
            if (jars.isEmpty()) {
                throw new IllegalStateException("No ktlint jars resolved upstream of the ktlint step");
            }
            files.sort(null);
            Path config = null;
            for (BuildStepArgument argument : arguments.values()) {
                Path candidate = argument.folder().resolve(".editorconfig");
                if (Files.isRegularFile(candidate)) {
                    config = candidate;
                    break;
                }
            }
            Path report = Files.createDirectories(context.next().resolve(BuildStep.REPORTS + "ktlint")).resolve("ktlint-report.xml");
            List<String> commands = new ArrayList<>(List.of(
                    "-cp", String.join(File.pathSeparator, jars),
                    "com.pinterest.ktlint.Main",
                    "--reporter=checkstyle,output=" + report));
            if (config != null) {
                commands.add("--editorconfig=" + config);
            }
            commands.addAll(files);
            return CompletableFuture.completedStage(commands);
        }
    }
}
