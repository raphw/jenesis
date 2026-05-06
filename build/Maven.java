package build;

import build.jenesis.BuildExecutor;
import build.jenesis.maven.MavenProject;
import build.jenesis.project.JavaModule;
import build.jenesis.step.Relocate;

import module java.base;

import static build.jenesis.project.MultiProjectModule.ARTIFACTS;
import static build.jenesis.project.MultiProjectModule.MANIFESTS;
import static build.jenesis.project.MultiProjectModule.SOURCES;

public class Maven {

    static void main(String[] args) throws IOException {
        BuildExecutor root = BuildExecutor.of(Path.of("target"));

        root.addModule("maven", MavenProject.make(Path.of("."),
                "SHA256",
                (_, _) -> (buildExecutor, _) -> buildExecutor.addModule("java",
                        new JavaModule().testIfAvailable(),
                        "../" + SOURCES, "../" + MANIFESTS, "../" + ARTIFACTS)));

        root.addStep("final", new Relocate(MavenProject.artifactsByModule()), "maven");

        root.execute();
    }
}
