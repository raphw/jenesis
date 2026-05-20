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
import build.jenesis.step.Download;
import build.jenesis.step.Java;
import build.jenesis.step.ProcessHandler;
import build.jenesis.step.Resolve;
import build.jenesis.step.TestEngine;

public class TestModule implements BuildExecutorModule {

    public static final String REQUIRED = "required", ARTIFACTS = "artifacts", EXECUTED = "executed";
    private static final String RESOLVED = "resolved";

    private final TestEngine engine;
    private final Predicate<String> isTest;
    private final Function<List<String>, ProcessHandler.OfProcess> factory;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final boolean jarsOnly;
    private final boolean modular;
    private final boolean requireEngine;
    private final boolean strictPinning;
    private final String filter;

    public TestModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(repositories, resolvers, null);
    }

    public TestModule(Map<String, Repository> repositories, Map<String, Resolver> resolvers, String filter) {
        this(null, defaultIsTest(), null, repositories, resolvers, true, true, true, false, filter);
    }

    public TestModule(TestEngine engine, Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this(engine, repositories, resolvers, null);
    }

    public TestModule(TestEngine engine,
                      Map<String, Repository> repositories,
                      Map<String, Resolver> resolvers,
                      String filter) {
        this(engine, defaultIsTest(), null, repositories, resolvers, true, true, true, false, filter);
    }

    public <P extends Predicate<String> & Serializable> TestModule(TestEngine engine,
                                                                   P isTest,
                                                                   Map<String, Repository> repositories,
                                                                   Map<String, Resolver> resolvers) {
        this(engine, isTest, repositories, resolvers, null);
    }

    public <P extends Predicate<String> & Serializable> TestModule(TestEngine engine,
                                                                   P isTest,
                                                                   Map<String, Repository> repositories,
                                                                   Map<String, Resolver> resolvers,
                                                                   String filter) {
        this(engine, isTest, null, repositories, resolvers, true, true, true, false, filter);
    }

    public <P extends Predicate<String> & Serializable> TestModule(
            Function<List<String>, ProcessHandler.OfProcess> factory,
            TestEngine engine,
            P isTest,
            Map<String, Repository> repositories,
            Map<String, Resolver> resolvers) {
        this(factory, engine, isTest, repositories, resolvers, null);
    }

    public <P extends Predicate<String> & Serializable> TestModule(
            Function<List<String>, ProcessHandler.OfProcess> factory,
            TestEngine engine,
            P isTest,
            Map<String, Repository> repositories,
            Map<String, Resolver> resolvers,
            String filter) {
        this(engine, isTest, factory, repositories, resolvers, true, true, true, false, filter);
    }

    private TestModule(TestEngine engine,
                       Predicate<String> isTest,
                       Function<List<String>, ProcessHandler.OfProcess> factory,
                       Map<String, Repository> repositories,
                       Map<String, Resolver> resolvers,
                       boolean jarsOnly,
                       boolean modular,
                       boolean requireEngine,
                       boolean strictPinning,
                       String filter) {
        this.engine = engine;
        this.isTest = isTest;
        this.factory = factory;
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.jarsOnly = jarsOnly;
        this.modular = modular;
        this.requireEngine = requireEngine;
        this.strictPinning = strictPinning;
        this.filter = filter;
    }

    private static Predicate<String> defaultIsTest() {
        List<Pattern> patterns = Stream.of(".*\\.Test[a-zA-Z0-9$]*", ".*\\..*Test", ".*\\..*Tests", ".*\\..*TestCase")
                .map(Pattern::compile)
                .toList();
        return (Predicate<String> & Serializable) (name -> patterns.stream().anyMatch(pattern ->
                pattern.matcher(name).matches()));
    }

    public TestModule jarsOnly(boolean jarsOnly) {
        return new TestModule(engine, isTest, factory, repositories, resolvers, jarsOnly, modular, requireEngine, strictPinning, filter);
    }

    public TestModule modular(boolean modular) {
        return new TestModule(engine, isTest, factory, repositories, resolvers, jarsOnly, modular, requireEngine, strictPinning, filter);
    }

    public TestModule requireEngine(boolean requireEngine) {
        return new TestModule(engine, isTest, factory, repositories, resolvers, jarsOnly, modular, requireEngine, strictPinning, filter);
    }

    public TestModule strictPinning(boolean strictPinning) {
        return new TestModule(engine, isTest, factory, repositories, resolvers, jarsOnly, modular, requireEngine, strictPinning, filter);
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
        buildExecutor.addStep(RESOLVED, new Requires(resolved, Set.copyOf(resolvers.keySet())), upstream);
        SequencedSet<String> resolveInputs = new LinkedHashSet<>();
        resolveInputs.add(RESOLVED);
        resolveInputs.addAll(upstream);
        buildExecutor.addStep(REQUIRED, new Resolve(repositories, resolvers, false), resolveInputs);
        buildExecutor.addStep(ARTIFACTS, new Download(repositories, strictPinning), REQUIRED);
        Run run = factory == null
                ? new Run(resolved, isTest, jarsOnly, modular, filter)
                : new Run(factory, resolved, isTest, jarsOnly, modular, filter);
        buildExecutor.addStep(EXECUTED, run,
                Stream.concat(upstream.stream(), Stream.of(ARTIFACTS)));
    }

    @Override
    public Optional<String> resolve(String path) {
        return path.equals(RESOLVED) ? Optional.empty() : Optional.of(path);
    }

    private record Requires(TestEngine engine, Set<String> prefixes) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            List<Path> folders = arguments.values().stream().map(BuildStepArgument::folder).toList();
            TestEngine resolved = engine != null ? engine : TestEngine.of(folders).orElse(null);
            SequencedProperties properties = new SequencedProperties();
            String selectedPrefix = null;
            if (resolved != null
                    && !resolved.coordinates().isEmpty()
                    && !TestEngine.hasRunner(resolved, folders)) {
                for (String coordinate : resolved.coordinates()) {
                    int index = coordinate.indexOf('/');
                    String prefix = index > 0 ? coordinate.substring(0, index) : "";
                    if (prefixes.contains(prefix)) {
                        properties.setProperty(coordinate, "");
                        selectedPrefix = prefix;
                        break;
                    }
                }
            }
            properties.store(context.next().resolve(BuildStep.REQUIRES));
            if (resolved != null && selectedPrefix != null) {
                SequencedProperties versions = new SequencedProperties();
                for (BuildStepArgument argument : arguments.values()) {
                    Path versionsFile = argument.folder().resolve(BuildStep.VERSIONS);
                    if (!Files.exists(versionsFile)) {
                        continue;
                    }
                    SequencedProperties upstream = SequencedProperties.ofFiles(versionsFile);
                    for (String key : upstream.stringPropertyNames()) {
                        int index = key.indexOf('/');
                        if (index > 0 && selectedPrefix.equals(key.substring(0, index))) {
                            versions.putIfAbsent(key, upstream.getProperty(key));
                        }
                    }
                }
                for (Map.Entry<String, String> entry : resolved.versions().entrySet()) {
                    String coordinate = entry.getKey();
                    int index = coordinate.indexOf('/');
                    String prefix = index > 0 ? coordinate.substring(0, index) : "";
                    if (selectedPrefix.equals(prefix)) {
                        versions.putIfAbsent(coordinate, entry.getValue());
                    }
                }
                if (!versions.isEmpty()) {
                    versions.store(context.next().resolve(BuildStep.VERSIONS));
                }
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private static class Run extends Java {

        private final TestEngine engine;
        private final Predicate<String> isTest;
        private final String filter;

        private Run(TestEngine engine, Predicate<String> isTest, boolean jarsOnly, boolean modular, String filter) {
            this.engine = engine;
            this.isTest = isTest;
            this.jarsOnly = jarsOnly;
            this.modular = modular;
            this.filter = filter;
        }

        private Run(Function<List<String>, ProcessHandler.OfProcess> factory,
                    TestEngine engine,
                    Predicate<String> isTest,
                    boolean jarsOnly,
                    boolean modular,
                    String filter) {
            super(factory);
            this.engine = engine;
            this.isTest = isTest;
            this.jarsOnly = jarsOnly;
            this.modular = modular;
            this.filter = filter;
        }

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            return filter != null || super.shouldRun(arguments);
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
            List<TestSelector> selectors = TestSelector.parse(filter);
            List<String> commands = new ArrayList<>();
            for (Map.Entry<String, String> entry : resolved.properties().entrySet()) {
                commands.add("-D" + entry.getKey() + "=" + entry.getValue());
            }
            if (modular && resolved.module() != null) {
                commands.add("--add-modules");
                commands.add("ALL-MODULE-PATH");
                commands.add("-m");
                commands.add(resolved.module() + "/" + resolved.mainClass());
            } else {
                commands.add(resolved.mainClass());
            }
            commands.addAll(resolved.arguments());
            for (BuildStepArgument argument : arguments.values()) {
                Path classes = argument.folder().resolve(CLASSES);
                if (Files.exists(classes)) {
                    Files.walkFileTree(classes, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (file.toString().endsWith(".class")) {
                                String raw = classes.relativize(file).toString();
                                String className = raw.substring(0, raw.length() - 6).replace(File.separatorChar, '.');
                                if (selectors.isEmpty()) {
                                    if (isTest.test(className)) {
                                        commands.add(resolved.classPrefix() + className);
                                    }
                                } else {
                                    for (TestSelector selector : selectors) {
                                        if (selector.classPattern.matcher(className).matches()) {
                                            if (selector.method == null) {
                                                commands.add(resolved.classPrefix() + className);
                                            } else {
                                                String methodPrefix = resolved.methodPrefix();
                                                if (methodPrefix == null) {
                                                    throw new IllegalStateException(
                                                            "Engine does not support method selection: " + resolved);
                                                }
                                                commands.add(methodPrefix + className + "#" + selector.method);
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
            return CompletableFuture.completedFuture(commands);
        }
    }

    private record TestSelector(Pattern classPattern, String method) {

        static List<TestSelector> parse(String spec) {
            if (spec == null || spec.isBlank()) {
                return List.of();
            }
            List<TestSelector> result = new ArrayList<>();
            for (String entry : spec.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int separator = trimmed.indexOf('#');
                if (separator < 0) {
                    result.add(new TestSelector(Pattern.compile(trimmed), null));
                } else {
                    result.add(new TestSelector(
                            Pattern.compile(trimmed.substring(0, separator)),
                            trimmed.substring(separator + 1)));
                }
            }
            return result;
        }
    }
}
