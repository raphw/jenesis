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

public class GroovyDocumentationModule implements BuildExecutorModule {

    public static final String DOCUMENTED = "documented";
    private static final String REQUIRED = "required", RESOLVED = "resolved", ARTIFACTS = "artifacts";

    private static final List<String> PREFERRED_PREFIXES = List.of("maven", "module");
    private static final String MODULE_NAME = "org.apache.groovy.groovydoc";
    private static final String MAVEN_GROUP = "org.apache.groovy";
    private static final String MAVEN_ARTIFACT = "groovy-groovydoc";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final String qualifier;
    private final String within;
    private final boolean includeJava;
    private final transient Function<List<String>, ? extends ProcessHandler> factory;

    public GroovyDocumentationModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, "groovydoc", null, false, null);
    }

    private GroovyDocumentationModule(Map<String, Repository> repositories,
                                      Map<String, Resolver> resolvers,
                                      Pinning pinning,
                                      String qualifier,
                                      String within,
                                      boolean includeJava,
                                      Function<List<String>, ? extends ProcessHandler> factory) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.qualifier = qualifier;
        this.within = within;
        this.includeJava = includeJava;
        this.factory = factory;
    }

    public GroovyDocumentationModule factory(Function<List<String>, ? extends ProcessHandler> factory) {
        return new GroovyDocumentationModule(repositories, resolvers, pinning, qualifier, within, includeJava, factory);
    }

    public GroovyDocumentationModule pinning(Pinning pinning) {
        return new GroovyDocumentationModule(repositories, resolvers, pinning, qualifier, within, includeJava, factory);
    }

    public GroovyDocumentationModule qualifier(String qualifier) {
        return new GroovyDocumentationModule(repositories, resolvers, pinning, qualifier, within, includeJava, factory);
    }

    public GroovyDocumentationModule within(String within) {
        return new GroovyDocumentationModule(repositories, resolvers, pinning, qualifier, within, includeJava, factory);
    }

    public GroovyDocumentationModule includeJava(boolean includeJava) {
        return new GroovyDocumentationModule(repositories, resolvers, pinning, qualifier, within, includeJava, factory);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        SequencedSet<String> upstream = inherited.sequencedKeySet();
        buildExecutor.addStep(REQUIRED, new Requires(Set.copyOf(resolvers.keySet()), qualifier), upstream);
        SequencedSet<String> resolveInputs = new LinkedHashSet<>();
        resolveInputs.add(REQUIRED);
        resolveInputs.addAll(upstream);
        buildExecutor.addStep(RESOLVED, new Resolve(repositories, resolvers, DependencyScope.RUNTIME).pinned(pinning != Pinning.IGNORE), resolveInputs);
        buildExecutor.addStep(ARTIFACTS, new Download(repositories).pinning(pinning).tag("compiler:" + qualifier), RESOLVED);
        SequencedSet<String> documentInputs = new LinkedHashSet<>();
        documentInputs.add(ARTIFACTS);
        documentInputs.addAll(upstream);
        buildExecutor.addStep(DOCUMENTED,
                factory == null ? new Document(within, includeJava) : new Document(within, includeJava, factory),
                documentInputs);
    }

    @Override
    public Optional<String> resolve(String path) {
        return switch (path) {
            case DOCUMENTED, RESOLVED, ARTIFACTS -> Optional.of(path);
            default -> Optional.empty();
        };
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
                        "No suitable resolver for Groovy documentation. Available prefixes: " + prefixes
                                + ". Expected one of: " + PREFERRED_PREFIXES);
            }
            String version = resolvedGroovyVersion(arguments);
            String namespace = qualifier == null ? selectedPrefix : selectedPrefix + "@" + qualifier;
            String coordinate = switch (selectedPrefix) {
                case "module" -> namespace + "/" + MODULE_NAME;
                case "maven" -> namespace + "/" + MAVEN_GROUP + "/" + MAVEN_ARTIFACT + "/" + version;
                default -> throw new IllegalStateException("Unreachable");
            };
            SequencedProperties requires = new SequencedProperties();
            requires.setProperty(coordinate, "");
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }

        private static String resolvedGroovyVersion(SequencedMap<String, BuildStepArgument> arguments) throws IOException {
            String marker = MAVEN_GROUP + "-" + MAVEN_GROUP.substring(MAVEN_GROUP.lastIndexOf('.') + 1) + "-";
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
                            int at = name.indexOf(marker);
                            if (at >= 0 && name.indexOf('@') < 0 && name.endsWith(".jar")) {
                                String candidate = name.substring(at + marker.length(), name.length() - ".jar".length());
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
        private final boolean includeJava;

        private Document(String within, boolean includeJava) {
            this(within, includeJava, ProcessHandler.OfProcess.ofJavaHome("bin/java"));
        }

        private Document(String within, boolean includeJava, Function<List<String>, ? extends ProcessHandler> factory) {
            super("groovydoc", factory);
            this.within = within;
            this.includeJava = includeJava;
        }

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            return Javac.hasRelevantChange(arguments,
                    Set.of(".groovy", ".java"),
                    Set.of("groovydoc.properties"));
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
            List<String> files = new ArrayList<>(), jars = new ArrayList<>(), classpath = new ArrayList<>();
            String release = null;
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
                Path sources = argument.folder().resolve(Bind.SOURCES);
                if (Files.exists(sources)) {
                    Files.walkFileTree(sources, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String name = file.toString();
                            if (name.endsWith(".groovy") || includeJava && name.endsWith(".java")) {
                                files.add(name);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
            for (SequencedMap<String, String> values : properties.values()) {
                String candidate = values.get("maven.compiler.release");
                if (candidate != null) {
                    release = candidate;
                }
            }
            files.sort(null);
            jars.sort(null);
            if (files.stream().noneMatch(name -> name.endsWith(".groovy"))) {
                return CompletableFuture.completedStage(null);
            }
            if (jars.isEmpty()) {
                throw new IllegalStateException(
                        "No groovydoc jars resolved upstream of the Groovy documentation step");
            }
            List<String> launch = new ArrayList<>();
            for (String jar : jars) {
                if (new File(jar).getName().indexOf('@') != -1) {
                    launch.add(jar);
                }
            }
            if (launch.isEmpty()) {
                launch = jars;
            }
            List<String> userClasspath = new ArrayList<>(jars);
            userClasspath.addAll(classpath);
            List<String> commands = new ArrayList<>(List.of(
                    "-cp", String.join(File.pathSeparator, launch),
                    "org.codehaus.groovy.tools.groovydoc.Main",
                    "-d", output.toString(),
                    "-classpath", String.join(File.pathSeparator, userClasspath),
                    "-javaVersion=" + (release == null ? "21" : release),
                    "-notimestamp"));
            commands.addAll(files);
            return CompletableFuture.completedStage(commands);
        }
    }
}
