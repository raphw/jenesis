package build;

import build.jenesis.BuildExecutor;
import build.jenesis.Repository;
import build.jenesis.module.DownloadModuleUris;
import build.jenesis.module.ModularProject;
import build.jenesis.project.JavaModule;
import build.jenesis.step.Bind;

import module java.base;

public class Modular {

    static void main(String[] args) throws IOException {
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
                                .filter(identity -> identity.startsWith("../../../")))))), "download");

        Function<Path, Optional<Path>> placement = file -> {
            String name = file.getFileName().toString();
            if (!"classes.jar".equals(name)) {
                return Optional.empty();
            }
            Path probe = file.getParent();
            while (probe != null) {
                Path parent = probe.getParent();
                if (parent != null
                        && parent.getFileName() != null
                        && "module".equals(parent.getFileName().toString())) {
                    return Optional.of(Path.of(probe.getFileName().toString(), name));
                }
                probe = parent;
            }
            return Optional.empty();
        };
        root.addStep("final", new Bind(placement), "build");

        root.execute();
    }
}
