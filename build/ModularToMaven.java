package build;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenUriParser;
import build.jenesis.maven.Pom;
import build.jenesis.module.DownloadModuleUris;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.module.ModularProject;
import build.jenesis.project.JavaModule;
import build.jenesis.project.MultiProjectModule;
import build.jenesis.step.Relocate;

public class ModularToMaven {

    static void main(String[] args) throws IOException {
        Map<String, Repository> repositories = Map.of("maven", new MavenDefaultRepository());

        BuildExecutor root = BuildExecutor.of(Path.of("target"));

        root.addStep("download", new DownloadModuleUris(null));

        root.addModule("build", (build, downloaded) -> {
            Function<String, String> parser = MavenUriParser.ofUris(new MavenUriParser(),
                    DownloadModuleUris.URIS,
                    downloaded.values());
            Map<String, Resolver> resolvers = Map.of("module", new ModularJarResolver(
                    false,
                    new MavenPomResolver().translated("maven", (_, coordinate) -> parser.apply(coordinate))));
            build.addModule("modules", ModularProject.make(
                    Path.of("."),
                    "SHA256",
                    repositories,
                    resolvers,
                    descriptor -> (buildExecutor, _) -> {
                        buildExecutor.addModule("java", new JavaModule().testIfAvailable(repositories, resolvers),
                                descriptor.sources(), descriptor.manifests(), descriptor.artifacts(), descriptor.runtimeArtifacts());
                        buildExecutor.addStep("pom", new Pom(),
                                descriptor.sources(), descriptor.manifests(), descriptor.checked());
                    }));
        }, "download");

        root.addStep("collect", new Relocate(MultiProjectModule.artifactsByModule()), "build");

        root.execute(args);
    }
}
