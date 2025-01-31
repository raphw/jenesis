package build.buildbuddy.project;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorModule;
import build.buildbuddy.step.JUnit;
import build.buildbuddy.step.Jar;
import build.buildbuddy.step.Javac;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.SequencedMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JavaModule implements BuildExecutorModule {

    public static final String ARTIFACTS = "artifacts", CLASSES = "classes", TESTS = "tests";

    public BuildExecutorModule tested() {
        return (buildExecutor, inherited) -> {
            accept(buildExecutor, inherited);
            buildExecutor.addStep(TESTS, new JUnit(), Stream.concat(
                    inherited.sequencedKeySet().stream(),
                    Stream.of(CLASSES, ARTIFACTS)).collect(Collectors.toCollection(LinkedHashSet::new)));
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
