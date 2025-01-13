package build;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.HashDigestFunction;
import build.buildbuddy.Repository;
import build.buildbuddy.Resolver;
import build.buildbuddy.maven.MavenDefaultRepository;
import build.buildbuddy.maven.MavenDefaultVersionNegotiator;
import build.buildbuddy.maven.MavenPomResolver;
import build.buildbuddy.maven.MavenRepository;
import build.buildbuddy.project.DependenciesModule;
import build.buildbuddy.project.JavaBuildModule;
import build.buildbuddy.step.Bind;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class Modules {

    public static void main(String[] args) throws IOException {
        MavenRepository mavenRepository = new MavenDefaultRepository();
        Map<String, Repository> repositories = Map.of("maven", mavenRepository);
        Map<String, Resolver> resolvers = Map.of("maven", new MavenPomResolver(
                mavenRepository,
                MavenDefaultVersionNegotiator.maven(mavenRepository)));

        BuildExecutor root = BuildExecutor.of(Path.of("target"), new HashDigestFunction("MD5"));
        root.addSource("dependencies", Path.of("dependencies"));

        root.addStep("main-deps", Bind.asDependencies("main.properties"), "dependencies");
        root.addModule("main-artifacts", new DependenciesModule(repositories, resolvers), "main-deps");
        root.addSource("main-sources", Bind.asSources(), Path.of("sources"));
        root.addModule("main", new JavaBuildModule(), "main-artifacts", "main-sources");

        root.addStep("test-deps", Bind.asDependencies("test.properties"), "dependencies");
        root.addModule("test-artifacts", new DependenciesModule(repositories, resolvers), "test-deps");
        root.addSource("test-sources", Bind.asSources(), Path.of("tests"));
        root.addModule("test", new JavaBuildModule().tests(), "test-artifacts", "test-sources", "main");

        Map<String, Path> steps = root.execute();
        System.out.println("Built: " + steps);
    }
}
