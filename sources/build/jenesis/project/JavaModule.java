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

    public static final String ARTIFACTS = "artifacts", CLASSES = "classes", VERSIONS = "versions", TEST = "test";

    public BuildExecutorModule testIfAvailable() {
        return test(false, null, null, null);
    }

    public BuildExecutorModule testIfAvailable(Map<String, Repository> repositories,
                                               Map<String, Resolver> resolvers) {
        return test(false, null, repositories, resolvers);
    }

    public BuildExecutorModule test(TestEngine engine) {
        return test(engine, null, null);
    }

    public BuildExecutorModule test(TestEngine engine,
                                    Map<String, Repository> repositories,
                                    Map<String, Resolver> resolvers) {
        return test(true, engine, repositories, resolvers);
    }

    public BuildExecutorModule test(boolean requireEngine, TestEngine engine) {
        return test(requireEngine, engine, null, null);
    }

    public BuildExecutorModule test(boolean requireEngine,
                                    TestEngine engine,
                                    Map<String, Repository> repositories,
                                    Map<String, Resolver> resolvers) {
        return (buildExecutor, inherited) -> {
            TestEngine candidate = engine;
            if (candidate == null) {
                candidate = TestEngine.of(() -> inherited.values().stream().iterator()).orElse(null);
                if (requireEngine && candidate == null) {
                    throw new IllegalStateException(
                            "No test engine could be resolved from inherited dependencies: "
                                    + inherited.sequencedKeySet());
                }
            }
            accept(buildExecutor, inherited);
            if (candidate != null) {
                TestModule tests = new TestModule(candidate);
                if (repositories != null && resolvers != null) {
                    tests = tests.withResolvers(repositories, resolvers);
                }
                buildExecutor.addModule(TEST, tests, Stream.concat(
                        Stream.of(CLASSES, ARTIFACTS),
                        inherited.sequencedKeySet().stream()));
            }
        };
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(CLASSES, process ? Javac.process() : Javac.tool(), inherited.sequencedKeySet());
        buildExecutor.addStep(VERSIONS, new Versions(), Stream.concat(
                Stream.of(CLASSES),
                inherited.sequencedKeySet().stream()));
        buildExecutor.addStep(ARTIFACTS, process ? Jar.process(Jar.Sort.CLASSES) : Jar.tool(Jar.Sort.CLASSES), Stream.concat(
                Stream.of(VERSIONS),
                inherited.sequencedKeySet().stream()));
    }

}
