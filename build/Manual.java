package build;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenRepository;
import build.jenesis.project.TestModule;
import build.jenesis.step.Bind;
import build.jenesis.step.Download;
import build.jenesis.step.Jar;
import build.jenesis.step.Javac;
import build.jenesis.step.Resolve;

public class Manual {

    static void main(String[] args) throws IOException {
        MavenRepository mavenRepository = new MavenDefaultRepository();
        Map<String, Repository> repositories = Map.of("maven", mavenRepository);
        Map<String, Resolver> resolvers = Map.of("maven", new MavenPomResolver());

        BuildExecutor root = BuildExecutor.of(Path.of("target"));
        root.addSource("deps", Path.of("dependencies"));

        root.addModule("main-deps", (module, _) -> {
            module.addStep("properties", Bind.asRequires("main.properties"), "../deps");
            module.addStep("resolved", new Resolve(repositories, resolvers, true), "properties");
            module.addStep("artifacts", new Download(repositories), "resolved");
        }, "deps");
        root.addModule("main", (module, _) -> {
            module.addSource("sources", Bind.asSources(), Path.of("sources"));
            module.addStep("classes", Javac.tool(), "sources", "../main-deps/artifacts");
            module.addStep("artifacts", Jar.tool(Jar.Sort.CLASSES), "classes");
        }, "main-deps");

        root.addModule("test-deps", (module, _) -> {
            module.addStep("properties", Bind.asRequires("test.properties"), "../deps");
            module.addStep("resolved", new Resolve(repositories, resolvers, true), "properties");
            module.addStep("artifacts", new Download(repositories), "resolved");
        }, "deps");
        root.addModule("test", (module, _) -> {
            module.addSource("sources", Bind.asSources(), Path.of("tests"));
            module.addStep("classes", Javac.tool(), "sources", "../main/artifacts", "../test-deps/artifacts");
            module.addStep("artifacts", Jar.tool(Jar.Sort.CLASSES), "classes", "../test-deps/artifacts");
            module.addModule("tests", new TestModule(repositories, resolvers), "classes", "artifacts", "../main/artifacts", "../test-deps/artifacts");
        }, "test-deps", "main");

        root.execute(args);
    }
}
