package build.jenesis.project;

import module java.base;
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
import build.jenesis.step.JdkProcessBuildStep;
import build.jenesis.step.ProcessBuildStep;
import build.jenesis.step.ProcessHandler;
import build.jenesis.step.Resolve;
import build.jenesis.step.Versions;

public class KotlinCompilerModule implements BuildExecutorModule {

    public static final String ARTIFACTS = "artifacts", CLASSES = "classes";
    private static final String REQUIRED = "required", RESOLVED = "resolved", COMPILED = "compiled";

    private static final List<String> PREFERRED_PREFIXES = List.of("module", "maven");
    private static final String MODULE_NAME = "kotlin.compiler.embeddable";
    private static final String MAVEN_GROUP = "org.jetbrains.kotlin";
    private static final String MAVEN_ARTIFACT = "kotlin-compiler-embeddable";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final boolean strictPinning;
    private final boolean includeResources;
    private final transient Function<List<String>, ? extends ProcessHandler> factory;

    public KotlinCompilerModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, false, true, null);
    }

    public KotlinCompilerModule(Map<String, Repository> repositories,
                                Map<String, Resolver> resolvers,
                                boolean strictPinning,
                                Function<List<String>, ? extends ProcessHandler> factory) {
        this(repositories, resolvers, strictPinning, true, factory);
    }

    private KotlinCompilerModule(Map<String, Repository> repositories,
                                 Map<String, Resolver> resolvers,
                                 boolean strictPinning,
                                 boolean includeResources,
                                 Function<List<String>, ? extends ProcessHandler> factory) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.strictPinning = strictPinning;
        this.includeResources = includeResources;
        this.factory = factory;
    }

    public KotlinCompilerModule strictPinning(boolean strictPinning) {
        return new KotlinCompilerModule(repositories, resolvers, strictPinning, includeResources, factory);
    }

    public KotlinCompilerModule includeResources(boolean includeResources) {
        return new KotlinCompilerModule(repositories, resolvers, strictPinning, includeResources, factory);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        SequencedSet<String> upstream = inherited.sequencedKeySet();
        buildExecutor.addStep(REQUIRED, new Requires(Set.copyOf(resolvers.keySet())), upstream);
        SequencedSet<String> resolveInputs = new LinkedHashSet<>();
        resolveInputs.add(REQUIRED);
        resolveInputs.addAll(upstream);
        buildExecutor.addStep(RESOLVED, new Resolve(repositories, resolvers, false), resolveInputs);
        buildExecutor.addStep(ARTIFACTS, new Download(repositories, strictPinning), RESOLVED);
        SequencedSet<String> compileInputs = new LinkedHashSet<>();
        compileInputs.add(ARTIFACTS);
        compileInputs.addAll(upstream);
        buildExecutor.addStep(COMPILED,
                factory == null ? new Compile(includeResources) : new Compile(includeResources, factory),
                compileInputs);
        buildExecutor.addStep(CLASSES, new Versions(), Stream.concat(
                Stream.of(COMPILED),
                compileInputs.stream()));
    }

    @Override
    public Optional<String> resolve(String path) {
        return path.equals(CLASSES) ? Optional.of(path) : Optional.empty();
    }

    private record Requires(Set<String> prefixes) implements BuildStep {

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            return arguments.values().stream().anyMatch(
                    argument -> argument.hasChanged(Path.of("kotlin.properties")));
        }

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            String version = null;
            Path versionFile = null;
            for (BuildStepArgument argument : arguments.values()) {
                Path file = argument.folder().resolve("kotlin.properties");
                if (Files.exists(file)) {
                    SequencedProperties loaded = SequencedProperties.ofFiles(file);
                    String declared = loaded.getProperty("version");
                    if (declared == null || declared.isEmpty()) {
                        throw new IllegalStateException("Missing 'version' property in " + file);
                    }
                    if (version != null && !version.equals(declared)) {
                        throw new IllegalStateException("Conflicting Kotlin compiler versions: "
                                + version + " (" + versionFile + ") vs. "
                                + declared + " (" + file + ")");
                    }
                    version = declared;
                    versionFile = file;
                }
            }
            if (version == null) {
                throw new IllegalStateException(
                        "No 'kotlin.properties' with a 'version' property found in upstream inputs");
            }
            String selectedPrefix = null;
            String coordinate = null;
            for (String prefix : PREFERRED_PREFIXES) {
                if (prefixes.contains(prefix)) {
                    selectedPrefix = prefix;
                    coordinate = switch (prefix) {
                        case "module" -> "module/" + MODULE_NAME;
                        case "maven" -> "maven/" + MAVEN_GROUP + "/" + MAVEN_ARTIFACT + "/" + version;
                        default -> throw new IllegalStateException("Unreachable");
                    };
                    break;
                }
            }
            if (selectedPrefix == null) {
                throw new IllegalStateException(
                        "No suitable resolver for Kotlin compiler. Available prefixes: " + prefixes
                                + ". Expected one of: " + PREFERRED_PREFIXES);
            }
            SequencedProperties requires = new SequencedProperties();
            requires.setProperty(coordinate, "");
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            if ("module".equals(selectedPrefix)) {
                SequencedProperties versions = new SequencedProperties();
                versions.setProperty(coordinate, version);
                versions.store(context.next().resolve(BuildStep.VERSIONS));
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private static class Compile extends JdkProcessBuildStep {

        private final boolean includeResources;

        private Compile(boolean includeResources) {
            this(includeResources, ProcessHandler.OfProcess.ofJavaHome("bin/java"));
        }

        private Compile(boolean includeResources, Function<List<String>, ? extends ProcessHandler> factory) {
            super("kotlinc", factory);
            this.includeResources = includeResources;
        }

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            return Javac.hasRelevantChange(arguments,
                    Set.of(".kt", ".java"),
                    Set.of("kotlinc.properties", "javac.properties"));
        }

        @Override
        public CompletionStage<List<String>> process(Executor executor,
                                                     BuildStepContext context,
                                                     SequencedMap<String, BuildStepArgument> arguments,
                                                     SequencedMap<String, SequencedMap<String, String>> properties)
                throws IOException {
            Path target = Files.createDirectory(context.next().resolve(CLASSES));
            List<String> files = new ArrayList<>(), jars = new ArrayList<>(), classpath = new ArrayList<>();
            String release = null;
            for (BuildStepArgument argument : arguments.values()) {
                Path javacProperties = argument.folder().resolve(ProcessBuildStep.PROCESS + "javac.properties");
                if (Files.exists(javacProperties)) {
                    SequencedProperties loaded = SequencedProperties.ofFiles(javacProperties);
                    String value = loaded.getProperty("--release");
                    if (value != null && !value.isEmpty()) {
                        release = value;
                    }
                }
                Path classes = argument.folder().resolve(CLASSES);
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
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                            Files.createDirectories(target.resolve(sources.relativize(dir)));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            String name = file.toString();
                            if (name.endsWith(".kt") || name.endsWith(".java")) {
                                files.add(name);
                            } else if (includeResources) {
                                BuildStep.linkOrCopy(target.resolve(sources.relativize(file)), file);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
            if (files.stream().noneMatch(name -> name.endsWith(".kt"))) {
                return CompletableFuture.completedStage(null);
            }
            if (jars.isEmpty()) {
                throw new IllegalStateException(
                        "No compiler jars resolved upstream of the Kotlin compile step");
            }
            for (List<String> entries : List.of(jars, classpath)) {
                for (String entry : entries) {
                    if (entry.indexOf(File.pathSeparatorChar) != -1) {
                        throw new IllegalArgumentException(
                                "Path entry contains separator '" + File.pathSeparator + "': " + entry);
                    }
                }
            }
            List<String> userClasspath = new ArrayList<>(jars);
            userClasspath.addAll(classpath);
            List<String> commands = new ArrayList<>(List.of(
                    "-cp", String.join(File.pathSeparator, jars),
                    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-d", target.toString(),
                    "-no-stdlib",
                    "-no-reflect",
                    "-classpath", String.join(File.pathSeparator, userClasspath)));
            if (release != null) {
                commands.add("-jvm-target");
                commands.add(release);
            }
            commands.addAll(files);
            return CompletableFuture.completedStage(commands);
        }
    }
}
