package build;

import build.jenesis.BuildExecutor;
import build.jenesis.Repository;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenUriParser;
import build.jenesis.maven.Pom;
import build.jenesis.module.DownloadModuleUris;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.module.ModularProject;
import build.jenesis.project.JavaModule;
import build.jenesis.step.Relocate;

import module java.base;

public class ModularByMaven {

    static void main(String[] args) throws IOException {
        Map<String, Repository> repositories = Map.of("maven", new MavenDefaultRepository());

        BuildExecutor root = BuildExecutor.of(Path.of("target"));

        root.addStep("download", new DownloadModuleUris(null, List.of(
                DownloadModuleUris.DEFAULT,
                Path.of("dependencies/modules.properties").toUri())));

        root.addModule("build", (build, downloaded) -> {
            Function<String, String> parser = MavenUriParser.ofUris(new MavenUriParser(),
                    DownloadModuleUris.URIS,
                    downloaded.values());
            build.addModule("modules", ModularProject.make(
                    Path.of("."),
                    "SHA256",
                    repositories,
                    Map.of("module", new ModularJarResolver(
                            false,
                            new MavenPomResolver().translated("maven", (_, coordinate) -> parser.apply(coordinate)))),
                    (_, _) -> (buildExecutor, _) -> {
                        buildExecutor.addModule("java", new JavaModule().testIfAvailable(),
                                "../sources", "../module-info", "../artifacts");
                        buildExecutor.addStep("pom", new Pom(),
                                "../sources", "../module-info", "../resolved");
                    }));
        }, "download");

        root.addStep("final", new Relocate(ModularProject.artifactsByModule()), "build");

        root.execute();
    }
}
