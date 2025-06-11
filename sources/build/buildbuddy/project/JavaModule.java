package build.buildbuddy.project;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorModule;
import build.buildbuddy.step.Jar;
import build.buildbuddy.step.Javac;
import build.buildbuddy.step.TestEngine;
import build.buildbuddy.step.Tests;

import module java.base;

public record JavaModule(boolean process) implements BuildExecutorModule {

    public JavaModule() {
        this(false);
    }

    public static final String ARTIFACTS = "artifacts", CLASSES = "classes", TESTS = "tests";

    public BuildExecutorModule testIfAvailable() {
        return test(null);
    }

    public BuildExecutorModule test(TestEngine engine) {
        return (buildExecutor, inherited) -> {
            TestEngine candidate = engine;
            if (candidate == null) {
                candidate = TestEngine.of(() -> inherited.values().stream().iterator()).orElse(null);
            }
            accept(buildExecutor, inherited);
            if (candidate != null) {
                buildExecutor.addStep(TESTS, new Tests(candidate), Stream.concat(
                        Stream.of(CLASSES, ARTIFACTS),
                        inherited.sequencedKeySet().stream()));
            }
        };
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(CLASSES, process ? Javac.process() : Javac.tool(), inherited.sequencedKeySet());
        buildExecutor.addStep(ARTIFACTS, process ? Jar.process() : Jar.tool(), Stream.concat(
                Stream.of(CLASSES),
                inherited.sequencedKeySet().stream()));
    }
}
