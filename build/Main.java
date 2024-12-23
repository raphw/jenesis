package build;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.HashDigestFunction;
import build.buildbuddy.step.Bind;
import build.buildbuddy.step.Jar;
import build.buildbuddy.step.Javac;

import java.nio.file.Path;
import java.util.Map;

public class Main {

    public static void main(String[] args) {
        BuildExecutor executor = new BuildExecutor(Path.of("target"), new HashDigestFunction("MD5"));
        executor.addSource("sources", Path.of("sources"));
        executor.addStep("sources-bound", Bind.asSources(), "sources");
        executor.addStep("sources-javac", new Javac(), "sources-bound");
        executor.addStep("sources-jar", new Jar(), "sources-javac");
        Map<String, Path> stages = executor.execute(Runnable::run).toCompletableFuture().join();
        System.out.println("Built: " + stages);
    }
}
