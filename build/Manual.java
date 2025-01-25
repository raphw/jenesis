package build;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildExecutorCallback;
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

        BuildExecutor root = BuildExecutor.of(Path.of("target"),
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.printing(System.out));
        root.addSource("deps", Path.of("dependencies"));

        root.addModule("main-deps", (module, _) -> {
            module.addStep("properties", Bind.asDependencies("main.properties"), "../deps");
            module.addStep("resolved", new Resolve(resolvers), "properties");
            module.addStep("artifacts", new Download(repositories), "resolved");
        }, "deps");
        root.addModule("main", (module, _) -> {
            module.addSource("sources", Bind.asSources(), Path.of("sources"));
            module.addStep("classes", new Javac(), "sources", "../main-deps/artifacts");
            module.addStep("artifacts", new Jar(), "classes");
        }, "main-deps");

        root.addModule("test-deps", (module, _) -> {
            module.addStep("properties", Bind.asDependencies("test.properties"), "../deps");
            module.addStep("resolved", new Resolve(resolvers), "properties");
            module.addStep("artifacts", new Download(repositories), "resolved");
        }, "deps");
        root.addModule("test", (module, _) -> {
            module.addSource("sources", Bind.asSources(), Path.of("tests"));
            module.addStep("classes", new Javac(), "sources", "../main/artifacts", "../test-deps/artifacts");
            module.addStep("artifacts", new Jar(), "classes", "../test-deps/artifacts");
            module.addStep("tests", new JUnit4(), "artifacts", "../main/artifacts", "../test-deps/artifacts");
        }, "test-deps", "main");

        System.out.println("Built: " + root.execute());
    }
}
