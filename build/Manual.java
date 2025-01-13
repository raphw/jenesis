package build;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.HashDigestFunction;
import build.buildbuddy.Repository;
import build.buildbuddy.Resolver;
import build.buildbuddy.maven.MavenDefaultRepository;
import build.buildbuddy.maven.MavenDefaultVersionNegotiator;
import build.buildbuddy.maven.MavenPomResolver;
import build.buildbuddy.maven.MavenRepository;
import build.buildbuddy.step.Bind;
import build.buildbuddy.step.Download;
import build.buildbuddy.step.JUnit4;
import build.buildbuddy.step.Jar;
import build.buildbuddy.step.Javac;
import build.buildbuddy.step.Resolve;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Manual {

    public static void main(String[] args) throws IOException {
        MavenRepository mavenRepository = new MavenDefaultRepository();
        Map<String, Repository> repositories = Map.of("maven", mavenRepository);
        Map<String, Resolver> resolvers = Map.of("maven", new MavenPomResolver(
                mavenRepository,
                MavenDefaultVersionNegotiator.maven(mavenRepository)));

        BuildExecutor root = BuildExecutor.of(Path.of("target"), new HashDigestFunction("MD5"));
        module.addSource("dependencies", Path.of("dependencies"));
        root.addModule("main-deps", (module, _) -> {
            module.addStep("bound", Bind.asDependencies("main.properties"), "../dependencies");
            module.addStep("resolved", new Resolve(resolvers), "bound");
            module.addStep("artifacts", new Download(repositories), "resolved");
        }, "dependencies");
        root.addModule("main", (module, _) -> {
            module.addSource("sources", Path.of("sources"));
            module.addStep("bound", Bind.asSources(), "sources");
            module.addStep("javac", new Javac(), "bound", "../main-dependencies/artifacts");
            module.addStep("jar", new Jar(), "javac");
        }, "main-deps");
        root.addModule("test-dependencies", (module, _) -> {
            module.addStep("bound", Bind.asDependencies("test.properties"), "../dependencies");
            module.addStep("resolved", new Resolve(resolvers), "bound");
            module.addStep("artifacts", new Download(repositories), "resolved");
        }, "dependencies");
        root.addModule("test", (module, _) -> {
            module.addSource("sources", Path.of("tests"));
            module.addStep("bound", Bind.asSources(), "sources");
            module.addStep("javac", new Javac(), "bound", "../main/jar", "../test-deps/artifacts");
            module.addStep("jar", new Jar(), "javac", "../test-deps/artifacts");
            module.addStep("junit", new JUnit4(), "jar", "../main/jar", "../test-deps/artifacts");
        }, "test-deps", "main");

        Map<String, Path> steps = root.execute();
        System.out.println("Built: " + steps);
    }
}
