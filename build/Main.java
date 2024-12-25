package build;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.HashDigestFunction;
import build.buildbuddy.maven.MavenRepository;
import build.buildbuddy.step.Bind;
import build.buildbuddy.step.JUnit4;
import build.buildbuddy.step.Jar;
import build.buildbuddy.step.Javac;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    public static void main(String[] args) {
        MavenRepository mavenRepository = new MavenRepository();
        BuildExecutor executor = new BuildExecutor(Path.of("target"), new HashDigestFunction("MD5"));
        executor.addSource("sources", Path.of("sources"));
        executor.addStep("sources-bound", Bind.asSources(), "sources");
        executor.addStep("sources-javac", new Javac(), "sources-bound");
        executor.addStep("sources-jar", new Jar(), "sources-javac");
        executor.addSource("test-sources", Path.of("tests"));
        executor.addStep("test-sources-bound", Bind.asSources(), "test-sources");
        executor.addStep("test-sources-javac", new Javac(), "sources-javac", "test-sources-bound");
        executor.addStep("junit", new JUnit4(), "test-sources-bound", "test-sources-javac");
        Map<String, Path> steps;
        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            steps = executor.execute(executorService).toCompletableFuture().join();
        }
        System.out.println("Built: " + steps);
    }
}
