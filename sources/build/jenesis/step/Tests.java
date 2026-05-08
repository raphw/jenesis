package build.jenesis.step;

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
import build.jenesis.project.DependenciesModule;

public class Tests implements BuildExecutorModule {

    public static final String RESOLVED = "resolved", PREPARE = "prepare", EXECUTION = "execute";

    private final TestEngine engine;
    private final Predicate<String> isTest;
    private final Function<List<String>, ProcessHandler.OfProcess> factory;

    private Map<String, Repository> repositories;
    private Map<String, Resolver> resolvers;
    private String checksum;
    private boolean jarsOnly = true;
    private boolean modular = true;

    public Tests() {
        this(null);
    }

    public Tests(TestEngine engine) {
        this.engine = engine;
        List<Pattern> patterns = Stream.of(".*\\.Test[a-zA-Z0-9$]*", ".*\\..*Test", ".*\\..*Tests", ".*\\..*TestCase")
                .map(Pattern::compile)
                .toList();
        isTest = (Predicate<String> & Serializable) (name -> patterns.stream().anyMatch(pattern ->
                pattern.matcher(name).matches()));
        factory = null;
    }

    public <P extends Predicate<String> & Serializable> Tests(TestEngine engine, P isTest) {
        this(engine, isTest, null);
    }

    public <P extends Predicate<String> & Serializable> Tests(Function<List<String>, ProcessHandler.OfProcess> factory,
                                                              TestEngine engine,
                                                              P isTest) {
        this(engine, isTest, factory);
    }

    private Tests(TestEngine engine,
                  Predicate<String> isTest,
                  Function<List<String>, ProcessHandler.OfProcess> factory) {
        this.engine = engine;
        this.isTest = isTest;
        this.factory = factory;
    }

    public Tests jarsOnly(boolean jarsOnly) {
        this.jarsOnly = jarsOnly;
        return this;
    }

    public Tests modular(boolean modular) {
        this.modular = modular;
        return this;
    }

    public Tests withResolvers(Map<String, Repository> repositories, Map<String, Resolver> resolvers) {
        this.repositories = repositories;
        this.resolvers = resolvers;
        return this;
    }

    public Tests computeChecksums(String algorithm) {
        this.checksum = algorithm;
        return this;
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        SequencedSet<String> upstream = inherited.sequencedKeySet();
        Stream<String> dependencies;
        if (repositories != null && resolvers != null) {
            buildExecutor.addStep(RESOLVED, new Requires(engine), upstream);
            DependenciesModule deps = new DependenciesModule(repositories, resolvers);
            if (checksum != null) {
                deps = deps.computeChecksums(checksum);
            }
            buildExecutor.addModule(PREPARE, deps, RESOLVED);
            dependencies = Stream.concat(
                    upstream.stream(),
                    Stream.of(PREPARE + "/" + DependenciesModule.ARTIFACTS));
        } else {
            dependencies = upstream.stream();
        }
        Run run = factory == null ? new Run(engine, isTest) : new Run(factory, engine, isTest);
        run.jarsOnly(jarsOnly).modular(modular);
        buildExecutor.addStep(EXECUTION, run, dependencies);
    }

    private record Requires(TestEngine engine) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            List<Path> folders = arguments.values().stream().map(BuildStepArgument::folder).toList();
            TestEngine resolved = engine != null ? engine : TestEngine.of(folders).orElse(null);
            Properties properties = new SequencedProperties();
            if (resolved != null
                    && !resolved.coordinates().isEmpty()
                    && !TestEngine.hasRunner(resolved, folders)) {
                for (String coordinate : resolved.coordinates()) {
                    properties.setProperty(coordinate, "");
                }
            }
            try (Writer writer = Files.newBufferedWriter(context.next().resolve(BuildStep.REQUIRES))) {
                properties.store(writer, null);
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private static class Run extends Java {

        private final TestEngine engine;
        private final Predicate<String> isTest;

        {
            jarsOnly = true;
        }

        Run(TestEngine engine, Predicate<String> isTest) {
            this.engine = engine;
            this.isTest = isTest;
        }

        Run(Function<List<String>, ProcessHandler.OfProcess> factory, TestEngine engine, Predicate<String> isTest) {
            super(factory);
            this.engine = engine;
            this.isTest = isTest;
        }

        @Override
        protected CompletionStage<List<String>> commands(Executor executor,
                                                         BuildStepContext context,
                                                         SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            TestEngine resolved = engine == null ? TestEngine
                                                   .of(() -> arguments.values().stream().map(BuildStepArgument::folder).iterator())
                                                   .orElseThrow(() -> new IllegalArgumentException("No test engine found")) : engine;
            List<String> commands = new ArrayList<>();
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
                                String className = raw.substring(0, raw.length() - 6).replace('/', '.');
                                if (isTest.test(className)) {
                                    commands.add(resolved.prefix() + className);
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
}
