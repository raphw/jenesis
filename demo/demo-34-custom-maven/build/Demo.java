package build;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.PathPlacement;
import build.jenesis.maven.MavenProject;
import build.jenesis.project.InferredMultiProjectAssembler;
import build.jenesis.project.ProjectModuleDescriptor;

/**
 * A "custom but not so custom" build: it does not go through {@code Project} (no
 * layout, no goals, no `java build/jenesis/Project.java`), yet it does not wire
 * every step by hand either. Instead it uses the convenience
 * {@code MavenProject.make(root, assembler)} overload, which discovers the
 * multi-module Maven project under {@code root} and supplies sane defaults for
 * the repositories, resolvers, and digest a normal build would configure.
 *
 * The only glue is the assembler: {@code make} hands each discovered module a
 * {@code MavenModuleDescriptor}, which is wrapped in a {@code ProjectModuleDescriptor}
 * so the stock {@code InferredMultiProjectAssembler} can wire the regular
 * compile/jar(/test) flow. This is the same toolchain {@code Project} uses, just
 * assembled directly here so you can see and adjust the wiring.
 *
 * Run from this directory with:
 *
 *     java build/Demo.java
 *
 * The multi-module project is built under {@code target/}; `greeter` is built
 * first and `app` resolves it from within the build.
 */
public class Demo {

    static void main(String[] args) throws Exception {
        BuildExecutor root = BuildExecutor.of(Path.of("target"));
        root.addModule("maven", MavenProject.make(Path.of("."),
                (descriptor, repositories, resolvers) -> new InferredMultiProjectAssembler().apply(
                        new ProjectModuleDescriptor(descriptor, new LinkedHashSet<>(List.of(Path.of("."))), true, false, false, null, PathPlacement.CLASS_PATH),
                        repositories,
                        resolvers)));
        root.execute(args);
    }
}
