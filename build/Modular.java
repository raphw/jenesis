package build;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.module.DownloadModuleUris;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.module.ModularProject;
import build.jenesis.project.JavaModule;
import build.jenesis.project.DependencyScope;
import build.jenesis.step.Relocate;

public class Modular {

    static void main(String[] args) throws IOException {
        BuildExecutor root = BuildExecutor.of(Path.of("target"));

        root.addStep("download", new DownloadModuleUris());

        root.addModule("build", (build, downloaded) -> {
            Map<String, Repository> repositories = Repository.ofProperties(DownloadModuleUris.URIS,
                    downloaded.values(),
                    (_, value) -> URI.create(value),
                    MavenDefaultRepository.versionResolver(),
                    Files.createDirectories(Path.of("cache/modules")));
            Map<String, Resolver> resolvers = Map.of("module", new ModularJarResolver(true));
            build.addModule("modules", ModularProject.make(
                    Path.of("."),
                    repositories,
                    resolvers,
                    (descriptor, mergedRepos, mergedResolvers) -> (buildExecutor, _) -> buildExecutor.addModule("java",
                            new JavaModule().testIfAvailable(mergedRepos, mergedResolvers),
                            descriptor.sources(),
                            descriptor.manifests(),
                            descriptor.resolved(DependencyScope.COMPILE),
                            descriptor.resolved(DependencyScope.RUNTIME),
                            descriptor.artifacts(DependencyScope.COMPILE),
                            descriptor.artifacts(DependencyScope.RUNTIME))));
        }, "download");

        root.addStep("collect", new Relocate(ModularProject.artifactsByModule()), "build");

        root.execute(args);
    }
}
