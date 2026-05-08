package build;

import build.jenesis.BuildExecutor;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenRepository;
import build.jenesis.project.DependenciesModule;
import build.jenesis.project.JavaModule;
import build.jenesis.step.Bind;
import build.jenesis.step.TestDefaultEngine;

import module java.base;

public class Modules {

    static void main(String[] args) throws IOException {
        MavenRepository mavenRepository = new MavenDefaultRepository();
        Map<String, Repository> repositories = Map.of("maven", mavenRepository);
        Map<String, Resolver> resolvers = Map.of("maven", new MavenPomResolver());

        Path target = Path.of("target");
        if (Boolean.getBoolean("jenesis.rebuild") && Files.exists(target)) {
            Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        BuildExecutor root = BuildExecutor.of(target);
        root.addSource("deps", Path.of("dependencies"));

        root.addStep("main-deps", Bind.asRequires("main.properties"), "deps");
        root.addModule("main-artifacts", new DependenciesModule(repositories, resolvers), "main-deps");
        root.addSource("main-sources", Bind.asSources(), Path.of("sources"));
        root.addModule("main", new JavaModule(), identifier -> identifier.equals("artifacts") ? Optional.of(identifier) : Optional.empty(), "main-artifacts", "main-sources");

        root.addStep("test-deps", Bind.asRequires("test.properties"), "deps");
        root.addModule("test-artifacts", new DependenciesModule(repositories, resolvers), "test-deps");
        root.addSource("test-sources", Bind.asSources(), Path.of("tests"));
        root.addModule("test", new JavaModule().test(TestDefaultEngine.JUNIT5), "test-artifacts", "test-sources", "main");

        root.execute(args);
    }
}
