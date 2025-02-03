package build.buildbuddy.project;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorModule;
import build.buildbuddy.step.Test;
import build.buildbuddy.step.Jar;
import build.buildbuddy.step.Javac;
import build.buildbuddy.step.TestEngine;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.SequencedMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JavaModule implements BuildExecutorModule {

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
                buildExecutor.addStep(TESTS, new Test(candidate), Stream.concat(
                        Stream.of(CLASSES, ARTIFACTS),
                        inherited.sequencedKeySet().stream()).collect(Collectors.toCollection(LinkedHashSet::new)));
            }
        };
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep(CLASSES, new Javac(), inherited.sequencedKeySet());
        buildExecutor.addStep(ARTIFACTS, new Jar(), Stream.concat(
                Stream.of(CLASSES),
                inherited.sequencedKeySet().stream()).collect(Collectors.toCollection(LinkedHashSet::new)));
    }
}
