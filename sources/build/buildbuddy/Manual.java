package build.buildbuddy;

import build.buildbuddy.maven.MavenDefaultRepository;
import build.buildbuddy.maven.MavenPomResolver;
import build.buildbuddy.maven.MavenRepository;
import build.buildbuddy.step.Bind;
import build.buildbuddy.step.Download;
import build.buildbuddy.step.Jar;
import build.buildbuddy.step.Javac;
import build.buildbuddy.step.Resolve;
import build.buildbuddy.step.Tests;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class Manual {

    public static void main(String[] args) throws IOException {
        MavenRepository mavenRepository = new MavenDefaultRepository();
        Map<String, Repository> repositories = Map.of("maven", mavenRepository);
        Map<String, Resolver> resolvers = Map.of("maven", new MavenPomResolver());

        BuildExecutor root = BuildExecutor.of(Path.of("target"));
        root.addSource("deps", Path.of("dependencies"));

        root.addModule("main-deps", (module, _) -> {
            module.addStep("properties", Bind.asDependencies("main.properties"), "../deps");
            module.addStep("resolved", new Resolve(repositories, resolvers), "properties");
            module.addStep("artifacts", new Download(repositories), "resolved");
        }, "deps");
        root.addModule("main", (module, _) -> {
            module.addSource("sources", Bind.asSources(), Path.of("sources"));
            module.addStep("classes", Javac.tool(), "sources", "../main-deps/artifacts");
            module.addStep("artifacts", Jar.tool(), "classes");
        }, "main-deps");

        root.addModule("test-deps", (module, _) -> {
            module.addStep("properties", Bind.asDependencies("test.properties"), "../deps");
            module.addStep("resolved", new Resolve(repositories, resolvers), "properties");
            module.addStep("artifacts", new Download(repositories), "resolved");
        }, "deps");
        root.addModule("test", (module, _) -> {
            module.addSource("sources", Bind.asSources(), Path.of("tests"));
            module.addStep("classes", Javac.tool(), "sources", "../main/artifacts", "../test-deps/artifacts");
            module.addStep("artifacts", Jar.tool(), "classes", "../test-deps/artifacts");
            module.addStep("tests", new Tests(), "artifacts", "../main/artifacts", "../test-deps/artifacts");
        }, "test-deps", "main");

        root.execute();
    }
}
