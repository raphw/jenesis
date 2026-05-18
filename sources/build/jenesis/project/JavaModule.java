package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.step.Jar;
import build.jenesis.step.Javac;
import build.jenesis.step.TestEngine;
import build.jenesis.step.Versions;

public record JavaModule(boolean process) implements BuildExecutorModule {

    public JavaModule() {
        this(false);
    }

    public static final String ARTIFACTS = "artifacts", CLASSES = "classes", TEST = "test";
    private static final String COMPILED = "compiled";

    public BuildExecutorModule testIfAvailable(Map<String, Repository> repositories,
                                               Map<String, Resolver> resolvers) {
        return test(false, null, repositories, resolvers);
    }

    public BuildExecutorModule test(TestEngine engine,
                                    Map<String, Repository> repositories,
                                    Map<String, Resolver> resolvers) {
        return test(true, engine, repositories, resolvers);
    }

    public BuildExecutorModule test(boolean requireEngine,
                                    TestEngine engine,
                                    Map<String, Repository> repositories,
                                    Map<String, Resolver> resolvers) {
        return new TestedJavaModule(this, requireEngine, engine, repositories, resolvers);
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(COMPILED, process ? Javac.process() : Javac.tool(), inherited.sequencedKeySet());
        buildExecutor.addStep(CLASSES, new Versions(), Stream.concat(
                Stream.of(COMPILED),
                inherited.sequencedKeySet().stream()));
        buildExecutor.addStep(ARTIFACTS, process ? Jar.process(Jar.Sort.CLASSES) : Jar.tool(Jar.Sort.CLASSES), Stream.concat(
                Stream.of(CLASSES),
                inherited.sequencedKeySet().stream()));
    }

    @Override
    public Optional<String> resolve(String path) {
        return path.equals(COMPILED) ? Optional.empty() : Optional.of(path);
    }

    private record TestedJavaModule(JavaModule javaModule,
                                    boolean requireEngine,
                                    TestEngine engine,
                                    Map<String, Repository> repositories,
                                    Map<String, Resolver> resolvers) implements BuildExecutorModule {

        @Override
        public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) throws IOException {
            TestEngine candidate = engine;
            if (candidate == null) {
                candidate = TestEngine.of(() -> inherited.values().stream().iterator()).orElse(null);
                if (requireEngine && candidate == null) {
                    throw new IllegalStateException(
                            "No test engine could be resolved from inherited dependencies: "
                                    + inherited.sequencedKeySet());
                }
            }
            javaModule.accept(buildExecutor, inherited);
            if (candidate != null) {
                buildExecutor.addModule(TEST, new TestModule(candidate, repositories, resolvers),
                        Stream.concat(
                                Stream.of(CLASSES, ARTIFACTS),
                                inherited.sequencedKeySet().stream()));
            }
        }

        @Override
        public Optional<String> resolve(String path) {
            return javaModule.resolve(path);
        }
    }
}
