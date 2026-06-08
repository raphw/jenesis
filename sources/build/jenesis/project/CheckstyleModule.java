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

public class CheckstyleModule implements BuildExecutorModule {

    public static final String CHECK = "check";
    private static final String REQUIRED = "required", DEPENDENCIES = "dependencies";

    private static final String MAVEN_GROUP = "com.puppycrawl.tools";
    private static final String MAVEN_ARTIFACT = "checkstyle";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String group;
    private final String configFile;
    private final boolean strict;
    private final transient Function<List<String>, ? extends ProcessHandler> factory;

    public CheckstyleModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "checkstyle", "checkstyle.xml", false, null);
    }

    private CheckstyleModule(Map<String, Repository> repositories,
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

    public CheckstyleModule pinning(Pinning pinning) {
        return new CheckstyleModule(repositories, resolvers, pinning, group, configFile, strict, factory);
    }

    public CheckstyleModule group(String group) {
        return new CheckstyleModule(repositories, resolvers, pinning, group, configFile, strict, factory);
    }

    public CheckstyleModule configFile(String configFile) {
        return new CheckstyleModule(repositories, resolvers, pinning, group, configFile, strict, factory);
    }

    public CheckstyleModule strict(boolean strict) {
        return new CheckstyleModule(repositories, resolvers, pinning, group, configFile, strict, factory);
    }

    public CheckstyleModule factory(Function<List<String>, ? extends ProcessHandler> factory) {
        return new CheckstyleModule(repositories, resolvers, pinning, group, configFile, strict, factory);
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
            super("checkstyle", factory);
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
            List<String> jars = new ArrayList<>(), files = new ArrayList<>();
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
                if (Files.exists(sources)) {
                    Files.walkFileTree(sources, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (file.toString().endsWith(".java")
                                    && !file.getFileName().toString().equals("module-info.java")) {
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
                throw new IllegalStateException("No Checkstyle jars resolved upstream of the Checkstyle step");
            }
            if (config == null) {
                throw new IllegalStateException("No " + configFile + " found among the inputs of the Checkstyle step");
            }
            files.sort(null);
            Path report = context.next().resolve("checkstyle-report.xml");
            List<String> commands = new ArrayList<>(List.of(
                    "-cp", String.join(File.pathSeparator, jars),
                    "com.puppycrawl.tools.checkstyle.Main",
                    "-c", config.toString(),
                    "-f", "xml",
                    "-o", report.toString()));
            commands.addAll(files);
            return CompletableFuture.completedStage(commands);
        }
    }
}
