package build;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.Repository;
import build.buildbuddy.module.DownloadModuleUris;
import build.buildbuddy.module.ModularProject;
import build.buildbuddy.project.JavaModule;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Modular {

    public static void main(String[] args) throws IOException {
        BuildExecutor root = BuildExecutor.of(Path.of("target"));
        root.addStep("download", new DownloadModuleUris("module", List.of(
                DownloadModuleUris.DEFAULT,
                Path.of("dependencies/modules.properties").toUri())));
        root.addModule("build", (build, downloaded) -> build.addModule("modules", ModularProject.make(
                Path.of("."),
                "SHA256",
                Repository.ofProperties(DownloadModuleUris.URIS,
                        downloaded.values(),
                        URI::create,
                        Files.createDirectories(Path.of("cache/modules"))),
                (_, _) -> (buildExecutor, inherited) -> buildExecutor.addModule("java",
                        new JavaModule().testIfAvailable(),
                        Stream.concat(Stream.of("../dependencies/artifacts"), inherited.sequencedKeySet().stream()
                                .filter(identity -> identity.startsWith("../../../"))).collect(
                                Collectors.toCollection(LinkedHashSet::new))))), "download");
        root.execute();
    }
}
