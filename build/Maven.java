package build;

import build.jenesis.BuildExecutor;
import build.jenesis.maven.MavenProject;
import build.jenesis.project.JavaModule;
import build.jenesis.step.Relocate;

import module java.base;

public class Maven {

    static void main(String[] args) throws IOException {
        BuildExecutor root = BuildExecutor.of(Path.of("target"));

        root.addModule("maven", MavenProject.make(Path.of("."),
                "SHA256",
                (_, _) -> (buildExecutor, _) -> buildExecutor.addModule("java",
                        new JavaModule().testIfAvailable(),
                        "../sources", "../declare", "../artifacts")));

        root.addStep("final", new Relocate(MavenProject.artifactsByModule()), "maven");

        root.execute();
    }
}
