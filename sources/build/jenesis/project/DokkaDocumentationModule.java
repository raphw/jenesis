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
import build.jenesis.step.Bind;
import build.jenesis.step.Download;
import build.jenesis.step.Javac;
import build.jenesis.step.Javadoc;
import build.jenesis.step.JdkProcessBuildStep;
import build.jenesis.step.ProcessHandler;
import build.jenesis.step.Resolve;

public class DokkaDocumentationModule implements BuildExecutorModule {

    public static final String DOCUMENTED = "documented";
    private static final String REQUIRED = "required", RESOLVED = "resolved", ARTIFACTS = "artifacts";

    private static final String MAVEN_GROUP = "org.jetbrains.dokka";
    private static final List<String> CLI_ARTIFACTS = List.of(
            "dokka-cli", "analysis-kotlin-descriptors", "javadoc-plugin");

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String qualifier;
    private final String within;
    private final transient Function<List<String>, ? extends ProcessHandler> factory;

    public DokkaDocumentationModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "dokka", null, null);
    }

    private DokkaDocumentationModule(Map<String, Repository> repositories,
                                     Map<String, Resolver> resolvers,
                                     Pinning pinning,
                                     String qualifier,
                                     String within,
                                     Function<List<String>, ? extends ProcessHandler> factory) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.qualifier = qualifier;
        this.within = within;
        this.factory = factory;
    }

    public DokkaDocumentationModule factory(Function<List<String>, ? extends ProcessHandler> factory) {
        return new DokkaDocumentationModule(repositories, resolvers, pinning, qualifier, within, factory);
    }

    public DokkaDocumentationModule pinning(Pinning pinning) {
        return new DokkaDocumentationModule(repositories, resolvers, pinning, qualifier, within, factory);
    }

    public DokkaDocumentationModule qualifier(String qualifier) {
        return new DokkaDocumentationModule(repositories, resolvers, pinning, qualifier, within, factory);
    }

    public DokkaDocumentationModule within(String within) {
        return new DokkaDocumentationModule(repositories, resolvers, pinning, qualifier, within, factory);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        SequencedSet<String> upstream = inherited.sequencedKeySet();
        buildExecutor.addStep(REQUIRED, new Requires(resolvers.containsKey("maven"), qualifier), upstream);
        SequencedSet<String> resolveInputs = new LinkedHashSet<>();
        resolveInputs.add(REQUIRED);
        resolveInputs.addAll(upstream);
        buildExecutor.addStep(RESOLVED, new Resolve(repositories, resolvers, DependencyScope.RUNTIME).pinned(pinning != Pinning.IGNORE), resolveInputs);
        buildExecutor.addStep(ARTIFACTS, new Download(repositories).pinning(pinning).tag("compiler:" + qualifier), RESOLVED);
        SequencedSet<String> documentInputs = new LinkedHashSet<>();
        documentInputs.add(ARTIFACTS);
        documentInputs.addAll(upstream);
        buildExecutor.addStep(DOCUMENTED,
                factory == null ? new Document(within, qualifier) : new Document(within, qualifier, factory),
                documentInputs);
    }

    @Override
    public Optional<String> resolve(String path) {
        return switch (path) {
            case DOCUMENTED, RESOLVED, ARTIFACTS -> Optional.of(path);
            default -> Optional.empty();
        };
    }

    private record Requires(boolean maven, String qualifier) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            SequencedProperties requires = new SequencedProperties();
            if (maven) {
                String namespace = qualifier == null ? "maven" : "maven@" + qualifier;
                for (String artifact : CLI_ARTIFACTS) {
                    requires.setProperty(namespace + "/" + MAVEN_GROUP + "/" + artifact + "/RELEASE", "");
                }
            }
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private static class Document extends JdkProcessBuildStep {

        private final String within;
        private final String qualifierTrail;

        private Document(String within, String qualifierTrail) {
            this(within, qualifierTrail, ProcessHandler.OfProcess.ofJavaHome("bin/java"));
        }

        private Document(String within, String qualifierTrail, Function<List<String>, ? extends ProcessHandler> factory) {
            super("dokka", factory);
            this.within = within;
            this.qualifierTrail = qualifierTrail;
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
                for (String jarFolder : List.of(ARTIFACTS, DEPENDENCIES)) {
                    Path jarRoot = argument.folder().resolve(jarFolder);
                    if (Files.exists(jarRoot)) {
                        Files.walkFileTree(jarRoot, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                jars.add(file.toString());
                                return FileVisitResult.CONTINUE;
                            }
                        });
                    }
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
            jars.sort(null);
            String cli = null;
            List<String> plugins = new ArrayList<>(), dependencies = new ArrayList<>();
            for (String jar : jars) {
                String name = new File(jar).getName();
                if (name.contains("dokka-cli")) {
                    cli = jar;
                } else if (name.contains("@" + qualifierTrail)) {
                    plugins.add(jar);
                } else if (name.indexOf('@') == -1) {
                    dependencies.add(jar);
                }
            }
            if (cli == null || plugins.isEmpty()) {
                return CompletableFuture.completedStage(null);
            }
            List<String> moduleClasspath = new ArrayList<>(dependencies);
            moduleClasspath.addAll(classpath);
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
