package build;

import build.jenesis.BuildExecutor;
import build.jenesis.Repository;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenUriParser;
import build.jenesis.module.DownloadModuleUris;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.module.ModularProject;
import build.jenesis.project.JavaModule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

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
                    (_, _) -> (buildExecutor, inherited) -> buildExecutor.addModule("java",
                            new JavaModule().testIfAvailable(),
                            Stream.concat(Stream.of("../dependencies/artifacts"), inherited.sequencedKeySet().stream()
                                    .filter(identity -> identity.startsWith("../../../"))))));
        }, "download");
        root.execute();
    }
}
