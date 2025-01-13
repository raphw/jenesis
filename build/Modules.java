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
import build.buildbuddy.project.JavaPipelineModule;
import build.buildbuddy.step.Bind;
import build.buildbuddy.step.Download;
import build.buildbuddy.step.JUnit4;
import build.buildbuddy.step.Jar;
import build.buildbuddy.step.Javac;
import build.buildbuddy.step.Resolve;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class Manual {

    public static void main(String[] args) throws IOException {
        MavenRepository mavenRepository = new MavenDefaultRepository();
        Map<String, Repository> repositories = Map.of("maven", mavenRepository);
        Map<String, Resolver> resolvers = Map.of("maven", new MavenPomResolver(
                mavenRepository,
                MavenDefaultVersionNegotiator.maven(mavenRepository)));

        BuildExecutor root = BuildExecutor.of(Path.of("target"), new HashDigestFunction("MD5"));
        root.addSource(DependenciesModule.DEPENDENCIES, Path.of("dependencies"));
        root.addSource("main-sources", Path.of("sources")); // TODO: add possibility to append step
        root.addSource("test-sources", Path.of("tests"));
        root.addModule("main-deps",
                new DependenciesModule(resolvers, repositories).withBinding("main.properties"),
                DependenciesModule.DEPENDENCIES);
        root.addModule("test-deps",
                new DependenciesModule(resolvers, repositories).withBinding("test.properties"),
                DependenciesModule.DEPENDENCIES);
        root.addModule("main", new JavaBuildModule(), "main-deps");
        root.addModule("test", new JavaBuildModule().withTests(), "test-deps", "main");

        Map<String, Path> steps = root.execute();
        System.out.println("Built: " + steps);
    }
}
