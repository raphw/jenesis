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
import build.jenesis.step.Javac;
import build.jenesis.step.Javadoc;
import build.jenesis.step.JdkProcessBuildStep;
import build.jenesis.step.ProcessBuildStep;
import build.jenesis.step.ProcessHandler;

public class DokkaDocumentationModule implements BuildExecutorModule {

    public static final String DOCUMENTED = "documented";
    private static final String REQUIRED = "required", ARTIFACTS = "artifacts",
            DEPENDENCIES = "dependencies";

    private static final String MAVEN_GROUP = "org.jetbrains.dokka";
    private static final List<String> CLI_ARTIFACTS = List.of(
            "dokka-cli", "dokka-base", "analysis-kotlin-descriptors");

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String tool;
    private final String group;
    private final String within;
    private final transient Function<List<String>, ? extends ProcessHandler> factory;
    private final Boolean printing;

    public DokkaDocumentationModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "dokka", "main", null, null, null);
    }

    private DokkaDocumentationModule(Map<String, Repository> repositories,
                                     Map<String, Resolver> resolvers,
                                     Pinning pinning,
                                     String tool,
                                     String group,
                                     String within,
                                     Function<List<String>, ? extends ProcessHandler> factory,
                                     Boolean printing) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.tool = tool;
        this.group = group;
        this.within = within;
        this.factory = factory;
        this.printing = printing;
    }

    public DokkaDocumentationModule factory(Function<List<String>, ? extends ProcessHandler> factory) {
        return new DokkaDocumentationModule(repositories, resolvers, pinning, tool, group, within, factory, printing);
    }

    public DokkaDocumentationModule pinning(Pinning pinning) {
        return new DokkaDocumentationModule(repositories, resolvers, pinning, tool, group, within, factory, printing);
    }

    public DokkaDocumentationModule tool(String tool) {
        return new DokkaDocumentationModule(repositories, resolvers, pinning, tool, group, within, factory, printing);
    }

    public DokkaDocumentationModule group(String group) {
        return new DokkaDocumentationModule(repositories, resolvers, pinning, tool, group, within, factory, printing);
    }

    public DokkaDocumentationModule within(String within) {
        return new DokkaDocumentationModule(repositories, resolvers, pinning, tool, group, within, factory, printing);
    }

    public DokkaDocumentationModule printing(boolean printing) {
        return new DokkaDocumentationModule(repositories, resolvers, pinning, tool, group, within, factory, printing);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        SequencedSet<String> upstream = inherited.sequencedKeySet();
        buildExecutor.addStep(REQUIRED, new Requires(resolvers.containsKey("maven"), tool), upstream);
        SequencedSet<String> resolveInputs = new LinkedHashSet<>();
        resolveInputs.add(REQUIRED);
        resolveInputs.addAll(upstream);
        buildExecutor.addStep(DEPENDENCIES,
                new Dependencies(repositories, resolvers).pinning(pinning),
                resolveInputs);
        SequencedSet<String> documentInputs = new LinkedHashSet<>();
        documentInputs.add(DEPENDENCIES);
        documentInputs.addAll(upstream);
        buildExecutor.addStep(DOCUMENTED,
                factory == null ? new Document(within, tool, group, printing) : new Document(within, tool, group, factory, printing),
                documentInputs);
    }

    @Override
    public Optional<String> resolve(String path) {
        if (path.equals(DOCUMENTED)) {
            return Optional.of(DOCUMENTED);
        }
        if (path.equals(DEPENDENCIES)) {
            return Optional.of(ARTIFACTS);
        }
        return Optional.empty();
    }

    private record Requires(boolean maven, String tool) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            SequencedProperties requires = new SequencedProperties();
            if (maven) {
                for (String artifact : CLI_ARTIFACTS) {
                    requires.setProperty(tool + "/runtime/maven/" + MAVEN_GROUP + "/" + artifact + "/RELEASE", "");
                }
            }
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private static class Document extends JdkProcessBuildStep {

        private final String within;
        private final String tool;
        private final String group;

        private Document(String within, String tool, String group, Boolean printing) {
            this(within, tool, group, ProcessHandler.OfProcess.ofJavaHome("bin/java"), printing);
        }

        private Document(String within, String tool, String group, Function<List<String>, ? extends ProcessHandler> factory, Boolean printing) {
            super("dokka", factory, printing == null ? ProcessBuildStep.printing("dokka") : printing);
            this.within = within;
            this.tool = tool;
            this.group = group;
        }

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            return Javac.hasRelevantChange(arguments,
                    Set.of(".kt"),
                    Set.of("dokka.properties"));
        }

        @Override
        public boolean acceptableExitCode(int code,
                                          Executor executor,
                                          BuildStepContext context,
                                          SequencedMap<String, BuildStepArgument> arguments) {
            return true;
        }

        @Override
        public CompletionStage<List<String>> process(Executor executor,
                                                     BuildStepContext context,
                                                     SequencedMap<String, BuildStepArgument> arguments,
                                                     SequencedMap<String, SequencedMap<String, String>> properties)
                throws IOException {
            Path documentation = context.next().resolve(Javadoc.JAVADOC);
            Path output = Files.createDirectories(within == null ? documentation : documentation.resolve(within));
            List<String> sources = new ArrayList<>(), jars = new ArrayList<>(), classpath = new ArrayList<>();
            boolean[] kotlin = new boolean[1];
            for (BuildStepArgument argument : arguments.values()) {
                Path classes = argument.folder().resolve(BuildStep.CLASSES);
                if (Files.exists(classes)) {
                    classpath.add(classes.toString());
                }
                for (Path jar : Dependencies.select(argument.folder(), tool, "runtime")) {
                    jars.add(jar.toString());
                }
                for (Path jar : Dependencies.select(argument.folder(), group, "compile")) {
                    classpath.add(jar.toString());
                }
                Path source = argument.folder().resolve(Bind.SOURCES);
                if (Files.exists(source)) {
                    boolean[] hasKotlin = new boolean[1];
                    Files.walkFileTree(source, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (file.getFileName().toString().endsWith(".kt")) {
                                hasKotlin[0] = true;
                                return FileVisitResult.TERMINATE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                    if (hasKotlin[0]) {
                        sources.add(source.toString());
                        kotlin[0] = true;
                    }
                }
            }
            if (!kotlin[0]) {
                return CompletableFuture.completedStage(null);
            }
            String cli = null;
            List<String> plugins = new ArrayList<>();
            for (String jar : jars) {
                if (new File(jar).getName().contains("dokka-cli")) {
                    cli = jar;
                } else {
                    plugins.add(jar);
                }
            }
            if (cli == null || plugins.isEmpty()) {
                return CompletableFuture.completedStage(null);
            }
            List<String> moduleClasspath = new ArrayList<>(classpath);
            StringBuilder sourceSet = new StringBuilder("-src ").append(String.join(";", sources));
            if (!moduleClasspath.isEmpty()) {
                sourceSet.append(" -classpath ").append(String.join(";", moduleClasspath));
            }
            sourceSet.append(" -analysisPlatform jvm");
            List<String> commands = new ArrayList<>(List.of(
                    "-jar", cli,
                    "-pluginsClasspath", String.join(";", plugins),
                    "-sourceSet", sourceSet.toString(),
                    "-outputDir", output.toString(),
                    "-moduleName", "documentation"));
            return CompletableFuture.completedStage(commands);
        }
    }
}
