package build;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.maven.MavenProject;
import build.jenesis.project.JavaModule;
import build.jenesis.step.Relocate;

public class Maven {

    static void main(String[] args) throws IOException {
        BuildExecutor root = BuildExecutor.of(Path.of("target"));

        root.addModule("build", MavenProject.make(Path.of("."),
                descriptor -> (buildExecutor, _) -> buildExecutor.addModule("java",
                        new JavaModule().testIfAvailable(),
                        descriptor.sources(), descriptor.manifests(), descriptor.artifacts(), descriptor.runtimeArtifacts())));

        root.addStep("collect", new Relocate(MavenProject.artifactsByModule()), "build");

        root.execute(args);
    }
}
