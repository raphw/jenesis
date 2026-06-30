package build.jenesis.project;

import module java.base;
import build.jenesis.step.Dependencies;
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
import build.jenesis.step.Javac;
import build.jenesis.step.JdkProcessBuildStep;
import build.jenesis.step.ProcessBuildStep;
import build.jenesis.step.ProcessHandler;
import build.jenesis.step.Versions;

public class ScalaCompilerModule implements BuildExecutorModule {

    public static final String ARTIFACTS = "artifacts", CLASSES = "classes";
    private static final String REQUIRED = "required", COMPILED = "compiled",
            DEPENDENCIES = "dependencies";

    private static final List<String> PREFERRED_PREFIXES = List.of("maven", "module");
    private static final String MODULE_NAME = "org.scala.lang.scala3.compiler";
    private static final String MAVEN_GROUP = "org.scala-lang";
    private static final String MAVEN_ARTIFACT = "scala3-compiler_3";

    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final Pinning pinning;
    private final boolean includeResources;
    private final String tool;
    private final String group;
    private final transient Function<List<String>, ? extends ProcessHandler> factory;

    public ScalaCompilerModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null, true, "scalac", "main", null);
    }

    private ScalaCompilerModule(Map<String, Repository> repositories,
                                Map<String, Resolver> resolvers,
                                Pinning pinning,
                                boolean includeResources,
                                String tool,
                                String group,
                                Function<List<String>, ? extends ProcessHandler> factory) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.pinning = pinning;
        this.includeResources = includeResources;
        this.tool = tool;
        this.group = group;
        this.factory = factory;
    }

    public ScalaCompilerModule factory(Function<List<String>, ? extends ProcessHandler> factory) {
        return new ScalaCompilerModule(repositories, resolvers, pinning, includeResources, tool, group, factory);
    }

    public ScalaCompilerModule pinning(Pinning pinning) {
        return new ScalaCompilerModule(repositories, resolvers, pinning, includeResources, tool, group, factory);
    }

    public ScalaCompilerModule includeResources(boolean includeResources) {
        return new ScalaCompilerModule(repositories, resolvers, pinning, includeResources, tool, group, factory);
    }

    public ScalaCompilerModule tool(String tool) {
        return new ScalaCompilerModule(repositories, resolvers, pinning, includeResources, tool, group, factory);
    }

    public ScalaCompilerModule group(String group) {
        return new ScalaCompilerModule(repositories, resolvers, pinning, includeResources, tool, group, factory);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        SequencedSet<String> upstream = inherited.sequencedKeySet();
        buildExecutor.addStep(REQUIRED, new Requires(Set.copyOf(resolvers.keySet()), tool), upstream);
        SequencedSet<String> resolveInputs = new LinkedHashSet<>();
        resolveInputs.add(REQUIRED);
        resolveInputs.addAll(upstream);
        buildExecutor.addStep(DEPENDENCIES,
                new Dependencies(repositories, resolvers).pinning(pinning),
                resolveInputs);
        SequencedSet<String> compileInputs = new LinkedHashSet<>();
        compileInputs.add(DEPENDENCIES);
        compileInputs.addAll(upstream);
        buildExecutor.addStep(COMPILED,
                factory == null ? new Compile(includeResources, tool, group) : new Compile(includeResources, tool, group, factory),
                compileInputs);
        buildExecutor.addStep(CLASSES, new Versions(), Stream.concat(
                Stream.of(COMPILED),
                compileInputs.stream()));
    }

    @Override
    public Optional<String> resolve(String path) {
        if (path.equals(CLASSES)) {
            return Optional.of(CLASSES);
        }
        if (path.equals(DEPENDENCIES)) {
            return Optional.of(ARTIFACTS);
        }
        return Optional.empty();
    }

    private record Requires(Set<String> prefixes, String tool) implements BuildStep {

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            return false;
        }

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
                        "No suitable resolver for Scala compiler. Available prefixes: " + prefixes
                                + ". Expected one of: " + PREFERRED_PREFIXES);
            }
            String coordinate = switch (selectedPrefix) {
                case "module" -> selectedPrefix + "/" + MODULE_NAME;
                case "maven" -> selectedPrefix + "/" + MAVEN_GROUP + "/" + MAVEN_ARTIFACT + "/RELEASE";
                default -> throw new IllegalStateException("Unreachable");
            };
            SequencedProperties requires = new SequencedProperties();
            requires.setProperty(tool + "/runtime/" + coordinate, "");
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private static class Compile extends JdkProcessBuildStep {

        private final boolean includeResources;
        private final String tool;
        private final String group;

        private Compile(boolean includeResources, String tool, String group) {
            this(includeResources, tool, group, ProcessHandler.OfProcess.ofJavaHome("bin/java"));
        }

        private Compile(boolean includeResources, String tool, String group, Function<List<String>, ? extends ProcessHandler> factory) {
            super("scalac", factory);
            this.includeResources = includeResources;
            this.tool = tool;
            this.group = group;
        }

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            return Javac.hasRelevantChange(arguments,
                    Set.of(".scala", ".java"),
                    Set.of("scalac.properties", "javac.properties"));
        }

        @Override
        public CompletionStage<List<String>> process(Executor executor,
                                                     BuildStepContext context,
                                                     SequencedMap<String, BuildStepArgument> arguments,
                                                     SequencedMap<String, SequencedMap<String, String>> properties)
                throws IOException {
            Path target = Files.createDirectory(context.next().resolve(CLASSES));
            List<String> files = new ArrayList<>(), jars = new ArrayList<>(), classpath = new ArrayList<>(),
                    plugins = new ArrayList<>();
            String release = null;
            for (BuildStepArgument argument : arguments.values()) {
                for (Path jar : Dependencies.select(argument.folder(), tool, "runtime")) {
                    jars.add(jar.toString());
                }
                for (Path jar : Dependencies.select(argument.folder(), tool, "plugin")) {
                    plugins.add(jar.toString());
                }
                for (Path jar : Dependencies.select(argument.folder(), group, "compile")) {
                    classpath.add(jar.toString());
                }
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
                            if (name.endsWith(".scala")
                                    || (name.endsWith(".java")
                                    && !file.getFileName().toString().equals("module-info.java"))) {
                                files.add(name);
                            } else if (includeResources
                                    && !name.endsWith(".java")
                                    && !BuildStep.underMetaInfVersions(sources.relativize(file))
                                    && !BuildStep.underBuildJenesis(sources.relativize(file))) {
                                BuildStep.linkOrCopy(target.resolve(sources.relativize(file)), file);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
            files.sort(null);
            if (files.stream().noneMatch(name -> name.endsWith(".scala"))) {
                return CompletableFuture.completedStage(null);
            }
            if (jars.isEmpty()) {
                throw new IllegalStateException(
                        "No compiler jars resolved upstream of the Scala compile step");
            }
            for (List<String> entries : List.of(jars, classpath, plugins)) {
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
                    "dotty.tools.dotc.Main",
                    "-d", target.toString(),
                    "-classpath", String.join(File.pathSeparator, userClasspath)));
            if (release != null) {
                commands.add("-release");
                commands.add(release);
            }
            for (String plugin : plugins) {
                commands.add("-Xplugin:" + plugin);
            }
            commands.addAll(files);
            return CompletableFuture.completedStage(commands);
        }
    }
}
