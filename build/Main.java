package build;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.HashDigestFunction;
import build.buildbuddy.Repository;
import build.buildbuddy.Resolver;
import build.buildbuddy.maven.MavenDefaultRepository;
import build.buildbuddy.maven.MavenDefaultVersionNegotiator;
import build.buildbuddy.maven.MavenPomResolver;
import build.buildbuddy.maven.MavenRepository;
import build.buildbuddy.step.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    public static void main(String[] args) throws IOException {
        MavenRepository mavenRepository = new MavenDefaultRepository();
        Map<String, Repository> repositories = Map.of("maven", mavenRepository);
        Map<String, Resolver> resolvers = Map.of("maven", new MavenPomResolver(
                mavenRepository,
                MavenDefaultVersionNegotiator.maven(mavenRepository)));
        BuildExecutor root = BuildExecutor.of(Path.of("target"), new HashDigestFunction("MD5"));
        root.add("sources", (module, _) -> {
            module.addSource("source", Path.of("sources"));
            module.addStep("bound", Bind.asSources(), "source");
            module.addStep("javac", new Javac(), "bound");
            module.addStep("jar", new Jar(), "javac");
        });
        root.add("tests", (module, _) -> {
            module.add("dependencies", (dependencies, _) -> {
                dependencies.addSource("properties", Path.of("dependencies", "test"));
                dependencies.addStep("bound", Bind.asDependencies(), "properties");
                dependencies.addStep("resolved", new Resolve(resolvers), "bound");
                dependencies.addStep("downloaded", new Download(repositories), "resolved");
            });
            module.addSource("source", Path.of("tests"));
            module.addStep("bound", Bind.asSources(), "source");
            module.addStep("javac", new Javac(), "bound", "../sources/jar", "dependencies/downloaded");
            module.addStep("jar", new Jar(), "javac", "dependencies/download");
            module.addStep("junit", new JUnit4(), "jar", "../sources/jar", "dependencies/downloaded");
        }, "sources");
        Map<String, Path> steps;
        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            steps = root.execute(executorService).toCompletableFuture().join();
        }
        System.out.println("Built: " + steps);
    }
}
