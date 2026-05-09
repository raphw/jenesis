package build.jenesis.project;

import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.step.Jar;
import build.jenesis.step.Javac;
import build.jenesis.step.TestEngine;
import build.jenesis.step.Tests;

import module java.base;

public record JavaModule(boolean process) implements BuildExecutorModule {

    public JavaModule() {
        this(false);
    }

    public static final String ARTIFACTS = "artifacts", CLASSES = "classes", TESTS = "tests";

    public BuildExecutorModule testIfAvailable() {
        return test(null, null, null);
    }

    public BuildExecutorModule testIfAvailable(Map<String, Repository> repositories,
                                               Map<String, Resolver> resolvers) {
        return test(null, repositories, resolvers);
    }

    public BuildExecutorModule test(TestEngine engine) {
        return test(engine, null, null);
    }

    public BuildExecutorModule test(TestEngine engine,
                                    Map<String, Repository> repositories,
                                    Map<String, Resolver> resolvers) {
        return (buildExecutor, inherited) -> {
            TestEngine candidate = engine;
            if (candidate == null) {
                candidate = TestEngine.of(() -> inherited.values().stream().iterator()).orElse(null);
            }
            accept(buildExecutor, inherited);
            if (candidate != null) {
                Tests tests = new Tests(candidate);
                if (repositories != null && resolvers != null) {
                    tests = tests.withResolvers(repositories, resolvers);
                }
                buildExecutor.addModule(TESTS, tests, Stream.concat(
                        Stream.of(CLASSES, ARTIFACTS),
                        inherited.sequencedKeySet().stream()));
            }
        };
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(CLASSES, process ? Javac.process() : Javac.tool(), inherited.sequencedKeySet());
        buildExecutor.addStep(ARTIFACTS, process ? Jar.process(Jar.Sort.CLASSES) : Jar.tool(Jar.Sort.CLASSES), Stream.concat(
                Stream.of(CLASSES),
                inherited.sequencedKeySet().stream()));
    }
}
