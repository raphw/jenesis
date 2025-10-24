package build.jenesis.project;

import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
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
        buildExecutor.addStep(ARTIFACTS, process ? Jar.process(Jar.Sort.CLASSES) : Jar.tool(Jar.Sort.CLASSES), Stream.concat(
                Stream.of(CLASSES),
                inherited.sequencedKeySet().stream()));
    }
}
