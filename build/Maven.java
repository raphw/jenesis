package build;

import build.jenesis.BuildExecutor;
import build.jenesis.maven.MavenProject;
import build.jenesis.project.JavaModule;
import build.jenesis.step.Bind;

import module java.base;

public class Maven {

    static void main(String[] args) throws IOException {
        BuildExecutor root = BuildExecutor.of(Path.of("target"));

        root.addModule("maven", MavenProject.make(Path.of("."),
                "SHA256",
                (_, _) -> (buildExecutor, inherited) -> buildExecutor.addModule("java",
                        new JavaModule().testIfAvailable(),
                        Stream.concat(Stream.of("../dependencies/artifacts"), inherited.sequencedKeySet().stream()
                                .filter(identity -> identity.startsWith("../../../"))))));

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
        root.addStep("final", new Bind(placement), "maven");

        root.execute();
    }
}
