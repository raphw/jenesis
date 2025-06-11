package build;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.Repository;
import build.buildbuddy.Resolver;
import build.buildbuddy.maven.MavenDefaultRepository;
import build.buildbuddy.maven.MavenPomResolver;
import build.buildbuddy.maven.MavenUriParser;
import build.buildbuddy.module.DownloadModuleUris;
import build.buildbuddy.module.ModularJarResolver;
import build.buildbuddy.module.ModularProject;
import build.buildbuddy.project.JavaModule;
import build.buildbuddy.step.Resolve;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ModularByMaven {

    public static void main(String[] args) throws IOException {
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
                                    .filter(identity -> identity.startsWith("../../../"))).collect(
                                    BuildExecutor.toSequencedSet()))));
        }, "download");
        root.execute();
    }
}
