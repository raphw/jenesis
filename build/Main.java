package build;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.HashDigestFunction;
import build.buildbuddy.Repository;
import build.buildbuddy.Resolver;
import build.buildbuddy.maven.MavenDefaultVersionNegotiator;
import build.buildbuddy.maven.MavenPomResolver;
import build.buildbuddy.maven.MavenRepository;
import build.buildbuddy.step.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {

    public static void main(String[] args) throws IOException {
        MavenRepository mavenRepository = new MavenRepository();
        Map<String, Repository> repositories = Map.of("file", Repository.files(), "maven", mavenRepository);
        Map<String, Resolver> resolvers = Map.of("file", Resolver.identity(), "maven", new MavenPomResolver(
                mavenRepository,
                MavenDefaultVersionNegotiator.maven(mavenRepository)));
        BuildExecutor executor = new BuildExecutor(
                Files.createDirectories(Path.of("target")),
                new HashDigestFunction("MD5"));
        executor.addSource("sources", Path.of("sources"));
        executor.addStep("sources-bound", Bind.asSources(), "sources");
        executor.addStep("sources-javac", new Javac(), "sources-bound");
        executor.addStep("sources-jar", new Jar(), "sources-javac");
        executor.addSource("test-sources", Path.of("tests"));
        executor.addStep("test-sources-bound", Bind.asSources(), "test-sources");
        executor.addSource("test-dependencies", Path.of("dependencies", "test"));
        executor.addStep("test-dependencies-bound", Bind.asDependencies(), "test-dependencies");
        executor.addStep("test-dependencies-resolved", new PropertyDependencies(
                resolvers,
                repositories,
                "SHA256"), "test-dependencies-bound");
        executor.addStep("test-dependencies-jar", new Dependencies(repositories), "test-dependencies-resolved");
        executor.addStep("test-sources-javac", new Javac(), "sources-jar", "test-dependencies-jar", "test-sources-bound");
        executor.addStep("junit", new JUnit4(), "sources-jar", "test-dependencies-jar", "test-sources-javac");
        Map<String, Path> steps;
        try (ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor()) {
            steps = executor.execute(executorService).toCompletableFuture().join();
        }
        System.out.println("Built: " + steps);
    }
}
