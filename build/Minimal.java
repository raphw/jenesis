package build;

import build.jenesis.BuildExecutor;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenRepository;
import build.jenesis.step.Bind;
import build.jenesis.step.Download;
import build.jenesis.step.Jar;
import build.jenesis.step.Javac;
import build.jenesis.step.Resolve;
import build.jenesis.step.TestEngine;
import build.jenesis.step.Tests;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class Minimal {

    static void main(String[] args) throws IOException {
        MavenRepository mavenRepository = new MavenDefaultRepository();
        Map<String, Repository> repositories = Map.of("maven", mavenRepository);
        Map<String, Resolver> resolvers = Map.of("maven", new MavenPomResolver());

        BuildExecutor root = BuildExecutor.of(Path.of("target"));

        root.addSource("sources", Bind.asSources(), Path.of("sources"));
        root.addStep("main-javac", Javac.tool(), "sources");
        root.addStep("main-jar", Jar.tool(Jar.Sort.CLASSES), "main-javac");

        root.addSource("test-dependencies", Bind.asDependencies("test.properties"), Path.of("dependencies"));
        root.addStep("test-dependencies-resolved", new Resolve(repositories, resolvers), "test-dependencies");
        root.addStep("test-dependencies-downloaded", new Download(repositories), "test-dependencies-resolved");

        root.addSource("test", Bind.asSources(), Path.of("tests"));
        root.addStep("test-javac", Javac.tool(), "main-jar", "test-dependencies-downloaded", "test");
        root.addStep("tests", new Tests(TestEngine.JUNIT5).jarsOnly(false), "main-jar", "test-dependencies-downloaded", "test-javac");

        root.execute();
    }
}
