package build;

import module java.base;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Execute;
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
 * The three-argument {@code InternalModule} constructor wires the default
 * Jenesis repository with the local export (~/.jenesis) prepended, so both the
 * plugin's {@code build.jenesis} and {@code org.json} dependencies resolve from
 * there - the demo downloads nothing explicitly.
 *
 * Run from this directory with:
 *
 *     java build/Demo.java
 *
 * which builds the project (the plugin rewrites {@code ${greeting}} first) and
 * then launches the built module, printing the substituted greeting.
 */
public class Demo {

    static void main(String[] args) throws Exception {
        Project project = new Project()
                .assembler(new PreprocessingAssembler(new JavaMultiProjectAssembler(), Path.of("plugin")));
        // Build the project (running the substitution plugin) and launch the
        // produced module so its main prints the rewritten greeting - the result
        // the plugin set out to produce. Execute reads the build's inventory to
        // find the module and its runtime, so nothing is located by hand.
        System.exit(new Execute(project).execute(args));
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
            // against its output. The project's sources are passed to the module
            // as inherited steps: InternalModule compiles the plugin in isolation
            // and forwards those sources to it only at run time, where the plugin
            // reads and rewrites them. The substituted copy it emits stands in for
            // them downstream.
            SequencedSet<String> original = descriptor.sources();
            SequencedSet<String> manifests = descriptor.manifests();
            ProjectModuleDescriptor redirected = descriptor.sources("preprocess/substitute");
            BuildExecutorModule inner = delegate.apply(redirected, repositories, resolvers);
            return (sub, inherited) -> {
                InternalModule preprocess = new InternalModule(
                        "module",                           // resolution prefix for the plugin's requires
                        "tool",                             // qualifier: an independent trail
                        pluginSource);
                // Forward the project's sources (to preprocess) and its manifests (the
                // @tool/ pin map): InternalModule reads the pins from the manifests and
                // resolves the plugin's build.jenesis/org.json closure against them.
                sub.addModule("preprocess", preprocess, Stream.concat(original.stream(), manifests.stream()));
                inner.accept(sub, inherited);
            };
        }
    }
}
