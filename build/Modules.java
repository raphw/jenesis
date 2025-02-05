package build;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.Repository;
import build.buildbuddy.Resolver;
import build.buildbuddy.maven.MavenDefaultRepository;
import build.buildbuddy.maven.MavenPomResolver;
import build.buildbuddy.maven.MavenRepository;
import build.buildbuddy.project.DependenciesModule;
import build.buildbuddy.project.JavaModule;
import build.buildbuddy.step.Bind;
import build.buildbuddy.step.TestEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class Modules {

    public static void main(String[] args) throws IOException {
        MavenRepository mavenRepository = new MavenDefaultRepository();
        Map<String, Repository> repositories = Map.of("maven", mavenRepository);
        Map<String, Resolver> resolvers = Map.of("maven", new MavenPomResolver());

        BuildExecutor root = BuildExecutor.of(Path.of("target"));
        root.addSource("deps", Path.of("dependencies"));

        root.addStep("main-deps", Bind.asDependencies("main.properties"), "deps");
        root.addModule("main-artifacts", new DependenciesModule(repositories, resolvers), "main-deps");
        root.addSource("main-sources", Bind.asSources(), Path.of("sources"));
        root.addModule("main", new JavaModule(), "main-artifacts", "main-sources");

        root.addStep("test-deps", Bind.asDependencies("test.properties"), "deps");
        root.addModule("test-artifacts", new DependenciesModule(repositories, resolvers), "test-deps");
        root.addSource("test-sources", Bind.asSources(), Path.of("tests"));
        root.addModule("test", new JavaModule().test(TestEngine.JUNIT5), "test-artifacts", "test-sources", "main/artifacts"); // TODO: main by itself contains too much
        // TODO: add resolver for paths
        root.execute();
    }
}
