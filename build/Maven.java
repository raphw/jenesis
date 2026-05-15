package build;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenProject;
import build.jenesis.project.JavaModule;
import build.jenesis.project.DependencyScope;
import build.jenesis.step.Relocate;

public class Maven {

    static void main(String[] args) throws IOException {
        Map<String, Repository> repositories = Map.of("maven", new MavenDefaultRepository());
        Map<String, Resolver> resolvers = Map.of("maven", new MavenPomResolver());

        BuildExecutor root = BuildExecutor.of(Path.of("target"));

        root.addModule("build", MavenProject.make(Path.of("."),
                descriptor -> (buildExecutor, _) -> buildExecutor.addModule("java",
                        new JavaModule().testIfAvailable(repositories, resolvers),
                        descriptor.sources(), descriptor.manifests(), descriptor.artifacts(DependencyScope.COMPILE), descriptor.artifacts(DependencyScope.RUNTIME))));

        root.addStep("collect", new Relocate(MavenProject.artifactsByModule()), "build");

        root.execute(args);
    }
}
