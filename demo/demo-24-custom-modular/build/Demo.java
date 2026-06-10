package build;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.PathPlacement;
import build.jenesis.module.ModularProject;
import build.jenesis.project.InferredMultiProjectAssembler;
import build.jenesis.project.ProjectModuleDescriptor;

/**
 * The modular counterpart of {@code ../custom-maven}: a "custom but not so
 * custom" build that does not go through {@code Project}, yet does not wire
 * every step by hand. It uses the convenience
 * {@code ModularProject.make(root, assembler)} overload, which discovers the
 * {@code module-info.java} modules under {@code root} and supplies sane defaults
 * for the repositories, resolvers, and digest a normal modular build configures.
 *
 * The only glue is the assembler: {@code make} hands each discovered module a
 * {@code ModularModuleDescriptor}, which is wrapped in a {@code ProjectModuleDescriptor}
 * (with {@code PathPlacement.MODULE_PATH}, since these are genuine modules)
 * so the stock {@code InferredMultiProjectAssembler} can wire the regular
 * compile/jar(/test) flow. This is the same toolchain {@code Project} uses for the
 * MODULAR layout, assembled directly here so the wiring is visible.
 *
 * Run from this directory with:
 *
 *     java build/Demo.java
 *
 * `greeter` is built first and `app`, which {@code requires demo.greeter},
 * resolves it from within the build. Each module produces a modular jar under
 * {@code target/}.
 */
public class Demo {

    static void main(String[] args) throws Exception {
        BuildExecutor root = BuildExecutor.of(Path.of("target"));
        root.addModule("modules", ModularProject.make(Path.of("."),
                (descriptor, repositories, resolvers) -> new InferredMultiProjectAssembler().apply(
                        new ProjectModuleDescriptor(descriptor, true, false, false, null, PathPlacement.MODULE_PATH),
                        repositories,
                        resolvers)));
        root.execute(args);
    }
}
