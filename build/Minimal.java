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
import build.buildbuddy.step.JUnit;
import build.buildbuddy.step.Jar;
import build.buildbuddy.step.Javac;
import build.buildbuddy.step.Resolve;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class Minimal {

    public static void main(String[] args) throws IOException {
        MavenRepository mavenRepository = new MavenDefaultRepository();
        Map<String, Repository> repositories = Map.of("maven", mavenRepository);
        Map<String, Resolver> resolvers = Map.of("maven", new MavenPomResolver(MavenDefaultVersionNegotiator.maven()));

        BuildExecutor executor = BuildExecutor.of(Path.of("target"),
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.printing(System.out));

        executor.addSource("sources", Bind.asSources(), Path.of("sources"));
        executor.addStep("main-javac", new Javac(), "sources");
        executor.addStep("main-jar", new Jar(), "main-javac");

        executor.addSource("test-dependencies", Bind.asDependencies("test.properties"), Path.of("dependencies"));
        executor.addStep("test-dependencies-resolved", new Resolve(repositories, resolvers), "test-dependencies");
        executor.addStep("test-dependencies-downloaded", new Download(repositories), "test-dependencies-resolved");

        executor.addSource("test", Bind.asSources(), Path.of("tests"));
        executor.addStep("test-javac", new Javac(), "main-jar", "test-dependencies-downloaded", "test");
        executor.addStep("tests", new JUnit(), "main-jar", "test-dependencies-downloaded", "test-javac");

        System.out.println("Built: " + executor.execute());
    }
}
