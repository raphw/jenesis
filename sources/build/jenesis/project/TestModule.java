package build.jenesis.project;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.PathPlacement;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Dependencies;
import build.jenesis.step.Java;
import build.jenesis.step.ProcessHandler;

public class TestModule implements BuildExecutorModule {

    public static final String REQUIRED = "required", ARTIFACTS = "artifacts", EXECUTED = "executed";
    private static final String RESOLVED = "resolved", DEPENDENCIES = "dependencies";

    private final TestEngine engine;
    private final Predicate<String> isTest;
    private final Function<List<String>, ProcessHandler.OfProcess> factory;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final boolean jarsOnly;
    private final boolean requireEngine;
    private final Pinning pinning;
    private final PathPlacement modulePath;
    private final String moduleName;
    private final String filter;
    private final String group;
    private final boolean parallel;
    private final boolean reporting;
    private final String dependencyGroup;
    private final List<ObservabilityEngine> observers;

    public TestModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        List<Pattern> patterns = Stream.of(
                        ".*\\.Test[a-zA-Z0-9$]*", ".*\\..*Test", ".*\\..*Tests", ".*\\..*TestCase",
                        ".*\\.IT[a-zA-Z0-9$]*", ".*\\..*IT", ".*\\..*ITCase")
                .map(Pattern::compile)
                .toList();
        this(null,
                (Predicate<String> & Serializable)
                        (name -> patterns.stream().anyMatch(pattern -> pattern.matcher(name).matches())),
                null,
                repositories,
                resolvers,
                true,
                true,
                null,
                PathPlacement.CLASS_PATH,
                null,
                System.getProperty("jenesis.test.filter"),
                System.getProperty("jenesis.test.group"),
                Boolean.getBoolean("jenesis.test.parallel"),
                Boolean.getBoolean("jenesis.test.reporting"),
                "main",
                List.of());
    }

    private TestModule(TestEngine engine,
                       Predicate<String> isTest,
                       Function<List<String>, ProcessHandler.OfProcess> factory,
                       Map<String, Repository> repositories,
                       Map<String, Resolver> resolvers,
                       boolean jarsOnly,
                       boolean requireEngine,
                       Pinning pinning,
                       PathPlacement modulePath,
                       String moduleName,
                       String filter,
                       String group,
                       boolean parallel,
                       boolean reporting,
                       String dependencyGroup,
                       List<ObservabilityEngine> observers) {
        this.engine = engine;
        this.isTest = isTest;
        this.factory = factory;
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.jarsOnly = jarsOnly;
        this.requireEngine = requireEngine;
        this.pinning = pinning;
        this.modulePath = modulePath;
        this.moduleName = moduleName;
        this.filter = filter;
        this.group = group;
        this.parallel = parallel;
        this.reporting = reporting;
        this.dependencyGroup = dependencyGroup;
        this.observers = observers;
    }

    public TestModule engine(TestEngine engine) {
        return new TestModule(engine,
                isTest,
                factory,
                repositories,
                resolvers,
                jarsOnly,
                requireEngine,
                pinning,
                modulePath,
                moduleName,
                filter,
                group,
                parallel,
                reporting,
                dependencyGroup,
                observers);
    }

    public <P extends Predicate<String> & Serializable> TestModule isTest(P isTest) {
        return new TestModule(engine,
                isTest,
                factory,
                repositories,
                resolvers,
                jarsOnly,
                requireEngine,
                pinning,
                modulePath,
                moduleName,
                filter,
                group,
                parallel,
                reporting,
                dependencyGroup,
                observers);
    }

    public TestModule factory(Function<List<String>, ProcessHandler.OfProcess> factory) {
        return new TestModule(engine,
                isTest,
                factory,
                repositories,
                resolvers,
                jarsOnly,
                requireEngine,
                pinning,
                modulePath,
                moduleName,
                filter,
                group,
                parallel,
                reporting,
                dependencyGroup,
                observers);
    }

    public TestModule filter(String filter) {
        return new TestModule(engine,
                isTest,
                factory,
                repositories,
                resolvers,
                jarsOnly,
                requireEngine,
                pinning,
                modulePath,
                moduleName,
                filter,
                group,
                parallel,
                reporting,
                dependencyGroup,
                observers);
    }

    public TestModule jarsOnly(boolean jarsOnly) {
        return new TestModule(engine,
                isTest,
                factory,
                repositories,
                resolvers,
                jarsOnly,
                requireEngine,
                pinning,
                modulePath,
                moduleName,
                filter,
                group,
                parallel,
                reporting,
                dependencyGroup,
                observers);
    }

    public TestModule requireEngine(boolean requireEngine) {
        return new TestModule(engine,
                isTest,
                factory,
                repositories,
                resolvers,
                jarsOnly,
                requireEngine,
                pinning,
                modulePath,
                moduleName,
                filter,
                group,
                parallel,
                reporting,
                dependencyGroup,
                observers);
    }

    public TestModule pinning(Pinning pinning) {
        return new TestModule(engine,
                isTest,
                factory,
                repositories,
                resolvers,
                jarsOnly,
                requireEngine,
                pinning,
                modulePath,
                moduleName,
                filter,
                group,
                parallel,
                reporting,
                dependencyGroup,
                observers);
    }

    public TestModule modulePath(PathPlacement modulePath) {
        return new TestModule(engine,
                isTest,
                factory,
                repositories,
                resolvers,
                jarsOnly,
                requireEngine,
                pinning,
                modulePath,
                moduleName,
                filter,
                group,
                parallel,
                reporting,
                dependencyGroup,
                observers);
    }

    public TestModule moduleName(String moduleName) {
        return new TestModule(engine,
                isTest,
                factory,
                repositories,
                resolvers,
                jarsOnly,
                requireEngine,
                pinning,
                modulePath,
                moduleName,
                filter,
                group,
                parallel,
                reporting,
                dependencyGroup,
                observers);
    }

    public TestModule group(String group) {
        return new TestModule(engine,
                isTest,
                factory,
                repositories,
                resolvers,
                jarsOnly,
                requireEngine,
                pinning,
                modulePath,
                moduleName,
                filter,
                group,
                parallel,
                reporting,
                dependencyGroup,
                observers);
    }

    public TestModule dependencyGroup(String dependencyGroup) {
        return new TestModule(engine,
                isTest,
                factory,
                repositories,
                resolvers,
                jarsOnly,
                requireEngine,
                pinning,
                modulePath,
                moduleName,
                filter,
                group,
                parallel,
                reporting,
                dependencyGroup,
                observers);
    }

    public TestModule parallel(boolean parallel) {
        return new TestModule(engine,
                isTest,
                factory,
                repositories,
                resolvers,
                jarsOnly,
                requireEngine,
                pinning,
                modulePath,
                moduleName,
                filter,
                group,
                parallel,
                reporting,
                dependencyGroup,
                observers);
    }

    public TestModule reporting(boolean reporting) {
        return new TestModule(engine,
                isTest,
                factory,
                repositories,
                resolvers,
                jarsOnly,
                requireEngine,
                pinning,
                modulePath,
                moduleName,
                filter,
                group,
                parallel,
                reporting,
                dependencyGroup,
                observers);
    }

    public TestModule observe(ObservabilityEngine... observers) {
        return new TestModule(engine,
                isTest,
                factory,
                repositories,
                resolvers,
                jarsOnly,
                requireEngine,
                pinning,
                modulePath,
                moduleName,
                filter,
                group,
                parallel,
                reporting,
                dependencyGroup,
                List.of(observers));
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
        TestEngine resolved = engine;
        if (resolved == null) {
            resolved = TestEngine.of(() -> inherited.values().stream().iterator()).orElse(null);
            if (resolved == null) {
                if (requireEngine) {
                    throw new IllegalStateException(
                            "No test engine could be resolved from inherited dependencies: "
                                    + inherited.sequencedKeySet());
                }
                return;
            }
        }
        SequencedSet<String> upstream = inherited.sequencedKeySet();
        buildExecutor.addStep(RESOLVED, new Requires(dependencyGroup, resolved, Set.copyOf(resolvers.keySet()), observers), upstream);
        SequencedSet<String> resolveInputs = new LinkedHashSet<>();
        resolveInputs.add(RESOLVED);
        resolveInputs.addAll(upstream);
        buildExecutor.addStep(DEPENDENCIES,
                new Dependencies(repositories, resolvers).pinning(pinning),
                resolveInputs);
        buildExecutor.addStep(EXECUTED, new Run(
                        factory,
                        resolved,
                        isTest,
                        jarsOnly,
                        modulePath,
                        moduleName,
                        filter,
                        group,
                        parallel,
                        reporting,
                        observers),
                Stream.concat(upstream.stream(), Stream.of(DEPENDENCIES)));
    }

    @Override
    public Optional<String> resolve(String path) {
        if (path.equals(EXECUTED)) {
            return Optional.of(EXECUTED);
        }
        if (path.equals(DEPENDENCIES)) {
            return Optional.of(ARTIFACTS);
        }
        return Optional.empty();
    }

    private record Requires(String group, TestEngine engine, Set<String> prefixes, List<ObservabilityEngine> observers) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            List<Path> folders = arguments.values().stream().map(BuildStepArgument::folder).toList();
            List<ModuleDescriptor> artifacts = TestEngine.scan(folders);
            TestEngine resolved = engine != null ? engine : TestEngine.of(artifacts).orElse(null);
            ModuleDescriptor engineModule = resolved == null ? null : resolved.match(artifacts).orElse(null);
            SequencedProperties properties = new SequencedProperties();
            SequencedProperties versions = new SequencedProperties();
            if (resolved != null && !resolved.hasRunner(artifacts)) {
                SequencedMap<String, String> runners = resolved.coordinates(engineModule);
                String selectedPrefix = null;
                for (String coordinate : runners.sequencedKeySet()) {
                    int index = coordinate.indexOf('/');
                    String prefix = index > 0 ? coordinate.substring(0, index) : "";
                    if (selectedPrefix == null && prefixes.contains(prefix)) {
                        selectedPrefix = prefix;
                    }
                    if (prefix.equals(selectedPrefix)) {
                        properties.setProperty(group + "/runtime/" + coordinate, "");
                    }
                }
                if (selectedPrefix != null) {
                    for (BuildStepArgument argument : arguments.values()) {
                        Path versionsFile = argument.folder().resolve(BuildStep.VERSIONS);
                        if (!Files.exists(versionsFile)) {
                            continue;
                        }
                        SequencedProperties upstream = SequencedProperties.ofFiles(versionsFile);
                        for (String key : upstream.stringPropertyNames()) {
                            if (key.startsWith(group + "/" + selectedPrefix + "/")) {
                                versions.putIfAbsent(key, upstream.getProperty(key));
                            }
                        }
                    }
                    for (Map.Entry<String, String> entry : runners.entrySet()) {
                        String coordinate = entry.getKey(), version = entry.getValue();
                        int index = coordinate.indexOf('/');
                        if (version != null && index > 0 && selectedPrefix.equals(coordinate.substring(0, index))) {
                            versions.putIfAbsent(group + "/" + coordinate, version);
                        }
                    }
                }
            }
            for (ObservabilityEngine observer : observers) {
                for (Map.Entry<String, String> entry : observer.coordinates().entrySet()) {
                    properties.setProperty(observer.name() + "/runtime/" + entry.getKey() + "/" + entry.getValue(), "");
                }
            }
            properties.store(context.next().resolve(BuildStep.REQUIRES));
            if (!versions.isEmpty()) {
                versions.store(context.next().resolve(BuildStep.VERSIONS));
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private static class Run extends Java {

        private final TestEngine engine;
        private final Predicate<String> isTest;
        private final String moduleName;
        private final String filter;
        private final String group;
        private final transient boolean parallel;
        private final boolean reporting;
        private final List<ObservabilityEngine> observers;

        private Run(Function<List<String>, ProcessHandler.OfProcess> factory,
                    TestEngine engine,
                    Predicate<String> isTest,
                    boolean jarsOnly,
                    PathPlacement modulePath,
                    String moduleName,
                    String filter,
                    String group,
                    boolean parallel,
                    boolean reporting,
                    List<ObservabilityEngine> observers) {
            super(factory == null ? ProcessHandler.OfProcess.ofJavaHome("bin/java") : factory, modulePath, jarsOnly);
            this.engine = engine;
            this.isTest = isTest;
            this.moduleName = moduleName;
            this.filter = filter;
            this.group = group;
            this.parallel = parallel;
            this.reporting = reporting;
            this.observers = observers;
        }

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            return filter != null || group != null || super.shouldRun(arguments);
        }

        @Override
        protected CompletionStage<List<String>> commands(Executor executor,
                                                         BuildStepContext context,
                                                         SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            TestEngine resolved = engine != null
                    ? engine
                    : TestEngine.of(() -> arguments.values().stream().map(BuildStepArgument::folder).iterator())
                    .orElseThrow(() -> new IllegalArgumentException("No test engine found"));
            List<TestSpec> specs = TestSpec.parse(filter);
            SequencedSet<String> groups = groups(group);
            List<String> commands = new ArrayList<>();
            for (ObservabilityEngine observer : observers) {
                commands.addAll(observer.commands(agentJars(arguments, observer), context.next()));
            }
            for (Map.Entry<String, String> entry : resolved.properties().entrySet()) {
                commands.add("-D" + entry.getKey() + "=" + entry.getValue());
            }
            if (modulePath.modular() && resolved.runnerModule() != null) {
                if (modulePath == PathPlacement.MODULE_PATH && moduleName != null) {
                    commands.add("--add-modules");
                    commands.add(moduleName);
                }
                commands.add("-m");
                commands.add(resolved.runnerModule() + "/" + resolved.mainClass());
            } else {
                commands.add(resolved.mainClass());
            }
            SequencedSet<String> matchedClasses = new TreeSet<>();
            SequencedMap<String, SequencedSet<String>> matchedMethods = new TreeMap<>();
            ClassFile classFile = ClassFile.of();
            for (BuildStepArgument argument : arguments.values()) {
                Path classes = argument.folder().resolve(CLASSES);
                if (Files.exists(classes)) {
                    Files.walkFileTree(classes, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (file.toString().endsWith(".class")) {
                                String raw = classes.relativize(file).toString();
                                String className = raw.substring(0, raw.length() - 6).replace(File.separatorChar, '.');
                                if ((classFile.parse(file).flags().flagsMask() & ClassFile.ACC_ABSTRACT) != 0) {
                                    return FileVisitResult.CONTINUE;
                                }
                                if (specs.isEmpty()) {
                                    if (isTest.test(className)) {
                                        matchedClasses.add(className);
                                    }
                                } else {
                                    for (TestSpec spec : specs) {
                                        if (spec.classPattern.matcher(className).matches()) {
                                            if (spec.method == null) {
                                                matchedClasses.add(className);
                                            } else {
                                                matchedMethods
                                                        .computeIfAbsent(className, _ -> new TreeSet<>())
                                                        .add(spec.method);
                                            }
                                            break;
                                        }
                                    }
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
            if (matchedClasses.isEmpty() && matchedMethods.isEmpty() && groups.isEmpty()) {
                throw new IllegalStateException("No tests matched the requested selection"
                        + (filter != null ? ", filter: " + filter : "")
                        + (group != null ? ", group: " + group : "")
                        + ". Adjust jenesis.test.filter / jenesis.test.group or the isTest predicate,"
                        + " or set jenesis.test.skip to skip testing.");
            }
            commands.addAll(resolved.commands(
                    context.supplement(),
                    context.next(),
                    matchedClasses,
                    matchedMethods,
                    groups,
                    parallel,
                    reporting));
            return CompletableFuture.completedFuture(commands);
        }

        private static SequencedMap<String, Path> agentJars(SequencedMap<String, BuildStepArgument> arguments,
                                                            ObservabilityEngine observer) throws IOException {
            SequencedMap<String, Path> resolved = new LinkedHashMap<>();
            for (BuildStepArgument argument : arguments.values()) {
                Path file = argument.folder().resolve(BuildStep.DEPENDENCIES);
                if (!Files.exists(file)) {
                    continue;
                }
                SequencedProperties properties = SequencedProperties.ofFiles(file);
                for (String coordinate : observer.coordinates().sequencedKeySet()) {
                    String prefix = observer.name() + "/runtime/" + coordinate + "/";
                    for (String key : properties.stringPropertyNames()) {
                        if (key.startsWith(prefix)) {
                            String value = properties.getProperty(key);
                            int space = value.indexOf(' ');
                            Path jar = argument.folder().resolve(space < 0 ? value : value.substring(0, space)).normalize();
                            if (Files.exists(jar)) {
                                resolved.putIfAbsent(coordinate, jar);
                            }
                        }
                    }
                }
            }
            return resolved;
        }

        private static SequencedSet<String> groups(String group) {
            if (group == null || group.isBlank()) {
                return Collections.emptyNavigableSet();
            }
            SequencedSet<String> groups = new LinkedHashSet<>();
            for (String entry : group.split(",")) {
                String trimmed = entry.trim();
                if (!trimmed.isEmpty()) {
                    groups.add(trimmed);
                }
            }
            return groups;
        }
    }

    private record TestSpec(Pattern classPattern, String method) {

        static List<TestSpec> parse(String spec) {
            if (spec == null || spec.isBlank()) {
                return List.of();
            }
            List<TestSpec> result = new ArrayList<>();
            for (String entry : spec.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int separator = trimmed.indexOf('#');
                if (separator < 0) {
                    result.add(new TestSpec(Pattern.compile(trimmed), null));
                } else {
                    result.add(new TestSpec(
                            Pattern.compile(trimmed.substring(0, separator)),
                            trimmed.substring(separator + 1)));
                }
            }
            return result;
        }
    }
}
