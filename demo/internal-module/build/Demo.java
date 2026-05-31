package build;

import module java.base;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Project;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.project.InternalModule;
import build.jenesis.project.JavaMultiProjectAssembler;
import build.jenesis.project.MultiProjectAssembler;
import build.jenesis.project.ProjectModuleDescriptor;

/**
 * Like the {@code custom-assembler} demo, this wraps the stock
 * {@code JavaMultiProjectAssembler} so the project's Java sources are
 * preprocessed (a {@code ${greeting}} substitution) before the regular compile,
 * jar, and test flow runs. The difference: the preprocessing is not an inline
 * build step but a build module loaded from local source with
 * {@code InternalModule}, and that module uses an external dependency
 * ({@code org.json}) to drive the substitution.
 *
 * Both the plugin's {@code build.jenesis} dependency and its {@code org.json}
 * dependency are resolved through the default Jenesis repository - the demo
 * downloads nothing explicitly.
 *
 * Run from this directory with:
 *
 *     java build/Demo.java
 */
public class Demo {

    static void main(String[] args) throws Exception {
        new Project()
                .assembler(new PreprocessingAssembler(new JavaMultiProjectAssembler(), Path.of("plugin")))
                .resolveProperties()
                .build(args);
    }

    private record PreprocessingAssembler(
            MultiProjectAssembler<? super ProjectModuleDescriptor> delegate,
            Path pluginSource)
            implements MultiProjectAssembler<ProjectModuleDescriptor> {

        @Override
        public BuildExecutorModule apply(ProjectModuleDescriptor descriptor,
                                         Map<String, Repository> repositories,
                                         Map<String, Resolver> resolvers) {
            // Load the preprocessing logic from the plugin/ build module via
            // InternalModule, and let the stock assembler wire the regular flow
            // against its output. The module shadows the project's sources: it
            // receives them (forwarded to the plugin's runtime so it can read and
            // rewrite them) but they are kept out of the plugin's own compilation,
            // and the substituted copy it emits stands in for them downstream.
            //
            // The three-argument InternalModule constructor wires the default
            // Jenesis repository, so the plugin's build.jenesis and org.json
            // dependencies resolve without any explicit download here.
            SequencedSet<String> original = descriptor.sources();
            ProjectModuleDescriptor redirected = descriptor.withSources("preprocess/substitute");
            BuildExecutorModule inner = delegate.apply(redirected, repositories, resolvers);
            return (sub, inherited) -> {
                InternalModule preprocess = new InternalModule(
                        "module",                           // resolution prefix for the plugin's requires
                        "tool",                             // qualifier: an independent trail
                        pluginSource).withShadowed(original);
                sub.addModule("preprocess", preprocess, original.stream());
                inner.accept(sub, inherited);
            };
        }
    }
}
