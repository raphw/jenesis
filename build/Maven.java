package build;

import build.jenesis.BuildExecutor;
import build.jenesis.maven.MavenProject;
import build.jenesis.project.JavaModule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;

public class Maven {

    static void main(String[] args) throws IOException {
        BuildExecutor root = BuildExecutor.of(Path.of("target"));
        root.addModule("maven", MavenProject.make(Path.of("."),
                "SHA256",
                (_, _) -> (buildExecutor, inherited) -> buildExecutor.addModule("java",
                        new JavaModule().testIfAvailable(),
                        Stream.concat(Stream.of("../dependencies/artifacts"), inherited.sequencedKeySet().stream()
                                .filter(identity -> identity.startsWith("../../../"))))));
        root.execute();
    }
}
