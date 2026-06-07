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
import build.jenesis.step.ProcessHandler;

public class ScalaDocumentationModule implements BuildExecutorModule {

    public static final String DOCUMENTED = "documented";
    private static final String REQUIRED = "required", RESOLVED = "resolved", ARTIFACTS = "artifacts",
            DEPENDENCIES = "dependencies";

    private static final List<String> PREFERRED_PREFIXES = List.of("maven", "module");
    private static final String MODULE_NAME = "org.scala.lang.scaladoc";
    private static final String MAVEN_GROUP = "org.scala-lang";
    private static final String MAVEN_ARTIFACT = "scaladoc_3";
    private static final String LIBRARY_MARKER = "org.scala-lang-scala3-library_3-";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String qualifier;
    private final String within;
    private final transient Function<List<String>, ? extends ProcessHandler> factory;

    public ScalaDocumentationModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "scaladoc", null, null);
    }

    private ScalaDocumentationModule(Map<String, Repository> repositories,
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

    public ScalaDocumentationModule factory(Function<List<String>, ? extends ProcessHandler> factory) {
        return new ScalaDocumentationModule(repositories, resolvers, pinning, qualifier, within, factory);
    }

    public ScalaDocumentationModule pinning(Pinning pinning) {
        return new ScalaDocumentationModule(repositories, resolvers, pinning, qualifier, within, factory);
    }

    public ScalaDocumentationModule qualifier(String qualifier) {
        return new ScalaDocumentationModule(repositories, resolvers, pinning, qualifier, within, factory);
    }

    public ScalaDocumentationModule within(String within) {
        return new ScalaDocumentationModule(repositories, resolvers, pinning, qualifier, within, factory);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        SequencedSet<String> upstream = inherited.sequencedKeySet();
        buildExecutor.addStep(REQUIRED, new Requires(Set.copyOf(resolvers.keySet()), qualifier), upstream);
        SequencedSet<String> resolveInputs = new LinkedHashSet<>();
        resolveInputs.add(REQUIRED);
        resolveInputs.addAll(upstream);
        buildExecutor.addModule(DEPENDENCIES,
                new DependenciesModule(repositories, resolvers).pinning(pinning),
                resolveInputs);
        SequencedSet<String> documentInputs = new LinkedHashSet<>();
        documentInputs.add(DEPENDENCIES + "/" + DependenciesModule.ARTIFACTS);
        documentInputs.addAll(upstream);
        buildExecutor.addStep(DOCUMENTED,
                factory == null ? new Document(within, qualifier) : new Document(within, qualifier, factory),
                documentInputs);
    }

    @Override
    public Optional<String> resolve(String path) {
        if (path.equals(DOCUMENTED)) {
            return Optional.of(DOCUMENTED);
        }
        if (path.equals(DEPENDENCIES + "/" + DependenciesModule.RESOLVED)) {
            return Optional.of(RESOLVED);
        }
        if (path.equals(DEPENDENCIES + "/" + DependenciesModule.ARTIFACTS)) {
            return Optional.of(ARTIFACTS);
        }
        return Optional.empty();
    }

    private record Requires(Set<String> prefixes, String qualifier) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            String selectedPrefix = null;
            for (String prefix : PREFERRED_PREFIXES) {
                if (prefixes.contains(prefix)) {
                    selectedPrefix = prefix;
                    break;
                }
            }
            if (selectedPrefix == null) {
                throw new IllegalStateException(
                        "No suitable resolver for Scala documentation. Available prefixes: " + prefixes
                                + ". Expected one of: " + PREFERRED_PREFIXES);
            }
            String version = resolvedVersion(arguments);
            String coordinate = switch (selectedPrefix) {
                case "module" -> selectedPrefix + "/" + MODULE_NAME;
                case "maven" -> selectedPrefix + "/" + MAVEN_GROUP + "/" + MAVEN_ARTIFACT + "/" + version;
                default -> throw new IllegalStateException("Unreachable");
            };
            SequencedProperties requires = new SequencedProperties();
            requires.setProperty(qualifier + "/" + qualifier + "/" + coordinate, "");
            if (selectedPrefix.equals("maven")) {
                requires.setProperty(qualifier + "/" + qualifier + "/" + selectedPrefix + "/com.fasterxml.jackson.core/jackson-annotations/2.21", "");
            }
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }

        private static String resolvedVersion(SequencedMap<String, BuildStepArgument> arguments) throws IOException {
            String[] found = new String[1];
            for (BuildStepArgument argument : arguments.values()) {
                for (String jarFolder : List.of(BuildStep.DEPENDENCIES, BuildStep.ARTIFACTS)) {
                    Path jarRoot = argument.folder().resolve(jarFolder);
                    if (!Files.exists(jarRoot)) {
                        continue;
                    }
                    Files.walkFileTree(jarRoot, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String name = file.getFileName().toString();
                            int at = name.indexOf(LIBRARY_MARKER);
                            if (at >= 0 && name.indexOf('@') < 0 && name.endsWith(".jar")) {
                                String candidate = name.substring(at + LIBRARY_MARKER.length(), name.length() - ".jar".length());
                                if (!candidate.isEmpty() && Character.isDigit(candidate.charAt(0))) {
                                    found[0] = candidate;
                                    return FileVisitResult.TERMINATE;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
            return found[0] == null ? "RELEASE" : found[0];
        }
    }

    private static class Document extends JdkProcessBuildStep {

        private final String within;
        private final String qualifier;

        private Document(String within, String qualifier) {
            this(within, qualifier, ProcessHandler.OfProcess.ofJavaHome("bin/java"));
        }

        private Document(String within, String qualifier, Function<List<String>, ? extends ProcessHandler> factory) {
            super("scaladoc", factory);
            this.within = within;
            this.qualifier = qualifier;
        }

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            return Javac.hasRelevantChange(arguments,
                    Set.of(".scala"),
                    Set.of("scaladoc.properties"));
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
            List<String> files = new ArrayList<>(), jars = new ArrayList<>(), classRoots = new ArrayList<>(),
                    classpath = new ArrayList<>();
            for (BuildStepArgument argument : arguments.values()) {
                Path classes = argument.folder().resolve(BuildStep.CLASSES);
                if (Files.exists(classes)) {
                    classRoots.add(classes.toString());
                }
                for (Path jar : Dependencies.select(argument.folder(), qualifier)) {
                    jars.add(jar.toString());
                }
                for (Path jar : Dependencies.select(argument.folder(), "compile")) {
                    classpath.add(jar.toString());
                }
                Path sources = argument.folder().resolve(Bind.SOURCES);
                if (Files.exists(sources)) {
                    Files.walkFileTree(sources, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String name = file.toString();
                            if (name.endsWith(".scala")) {
                                files.add(name);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
            files.sort(null);
            if (files.isEmpty()) {
                return CompletableFuture.completedStage(null);
            }
            if (jars.isEmpty()) {
                throw new IllegalStateException(
                        "No scaladoc jars resolved upstream of the Scala documentation step");
            }
            if (classRoots.isEmpty()) {
                return CompletableFuture.completedStage(null);
            }
            List<String> userClasspath = new ArrayList<>(classRoots);
            userClasspath.addAll(classpath);
            List<String> commands = new ArrayList<>(List.of(
                    "-cp", String.join(File.pathSeparator, jars),
                    "dotty.tools.scaladoc.Main",
                    "-d", output.toString(),
                    "-classpath", String.join(File.pathSeparator, userClasspath),
                    "-project", "documentation"));
            commands.addAll(classRoots);
            return CompletableFuture.completedStage(commands);
        }
    }
}
