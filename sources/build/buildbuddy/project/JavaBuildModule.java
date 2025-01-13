package build.buildbuddy.project;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorModule;
import build.buildbuddy.step.JUnit4;
import build.buildbuddy.step.Jar;
import build.buildbuddy.step.Javac;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.SequencedMap;
import java.util.SequencedSet;

public class JavaBuildModule implements BuildExecutorModule {

    public static final String JAR = "jar", ARTIFACTS = "artifacts";

    public BuildExecutorModule tests() {
        return (buildExecutor, inherited) -> {
            accept(buildExecutor, inherited);
            SequencedSet<String> dependencies = new LinkedHashSet<>(inherited.sequencedKeySet());
            dependencies.add(JAR);
            buildExecutor.addStep("test", new JUnit4(), dependencies);
        };
    }

    @Override
    public void accept(BuildExecutor buildExecutor, SequencedMap<String, Path> inherited) {
        buildExecutor.addStep("javac", new Javac(), inherited.sequencedKeySet());
        buildExecutor.addStep(JAR, new Jar(), "javac");
    }
}
