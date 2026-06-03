package build;

import module java.base;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Execute;
import build.jenesis.Project;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.Resolver;
import build.jenesis.module.JenesisModuleRepository;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.project.ExternalModule;
import build.jenesis.project.JavaMultiProjectAssembler;
import build.jenesis.project.MultiProjectAssembler;
import build.jenesis.project.ProjectModuleDescriptor;

/**
 * The {@code ExternalModule} counterpart of the {@code ../internal-module} demo.
 * It does exactly the same thing - wraps the stock {@code JavaMultiProjectAssembler}
 * so a build module preprocesses the project's sources (a {@code ${greeting}}
 * substitution driven by the {@code org.json} dependency) before the regular
 * flow - but the build module is consumed as a published artifact rather than
 * compiled from local source.
 *
 * To stand in for that published artifact, {@code main} first stages the build
 * module: it builds {@code plugin/} as its own modular project into a nested
 * {@code target/} folder and reads the produced jar straight from the build's
 * structured result (a fixed coordinate path under {@code stage/modular}), then
 * serves it under a custom coordinate. The custom {@code Project} wires that
 * coordinate as an {@code ExternalModule}.
 *
 * Run from this directory with:
 *
 *     java build/Demo.java
 *
 * which builds the project (the resolved plugin rewrites {@code ${greeting}}
 * first) and then launches the built module, printing the substituted greeting.
 */
public class Demo {

    static void main(String[] args) throws Exception {
        // Stage the build module as a published artifact: build plugin/ as its own
        // modular project at a fixed version and read the produced jar from the
        // build's structured result - stage/modular lays it out in the Jenesis
        // module repository's <module>/<version>/<module>.jar shape, so the path
        // is fully determined; nothing is located by scanning the filesystem.
        Files.createDirectories(Path.of("target"));
        Path modular = new Project()
                .root(Path.of("plugin"))
                .target(Path.of("target", "plugin"))
                .version("1")
                .build("stage")
                .get("stage/modular");
        Path pluginJar = modular.resolve("demo.plugin").resolve("1").resolve("demo.plugin.jar");

        // Serve the staged jar under the custom coordinate demo.plugin, ahead of
        // the local export (~/.jenesis) and the default Jenesis repository that
        // resolve its build.jenesis and org.json dependencies.
        Repository local = (executor, coordinate) -> {
            int slash = coordinate.indexOf('/');
            String module = slash < 0 ? coordinate : coordinate.substring(0, slash);
            return module.equals("demo.plugin")
                    ? Optional.of(RepositoryItem.ofFile(pluginJar))
                    : Optional.empty();
        };
        Repository repository = new JenesisModuleRepository(true)
                .prepend(JenesisModuleRepository.ofLocal())
                .prepend(local);

        // Build the project (the resolved plugin rewrites ${greeting} first) and
        // launch the produced module so its main prints the substituted greeting.
        Project project = new Project()
                .assembler(new PreprocessingAssembler(
                        new JavaMultiProjectAssembler(),
                        Map.of("module", repository),
                        Map.of("module", new ModularJarResolver(true))));
        System.exit(new Execute(project).execute(args));
    }

    private record PreprocessingAssembler(
            MultiProjectAssembler<? super ProjectModuleDescriptor> delegate,
            Map<String, Repository> pluginRepositories,
            Map<String, Resolver> pluginResolvers)
            implements MultiProjectAssembler<ProjectModuleDescriptor> {

        @Override
        public BuildExecutorModule apply(ProjectModuleDescriptor descriptor,
                                         Map<String, Repository> repositories,
                                         Map<String, Resolver> resolvers) {
            // Resolve the build module from its coordinate with ExternalModule and
            // let the stock assembler wire the regular flow against its output.
            // ExternalModule does not compile the plugin (it is pre-staged), so
            // the project sources are simply forwarded to the plugin's runtime;
            // the substituted copy it emits stands in for them downstream.
            SequencedSet<String> original = descriptor.sources();
            SequencedSet<String> manifests = descriptor.manifests();
            ProjectModuleDescriptor redirected = descriptor.withSources("preprocess/substitute");
            BuildExecutorModule inner = delegate.apply(redirected, repositories, resolvers);
            return (sub, inherited) -> {
                ExternalModule preprocess = new ExternalModule(
                        "module/demo.plugin",               // the coordinate to resolve
                        "tool",                             // qualifier: an independent trail
                        pluginRepositories,
                        pluginResolvers);
                // Forward the project's sources (to preprocess) and its manifests (the
                // @tool/ pin map): ExternalModule reads the pins from the manifests and
                // resolves the plugin's build.jenesis/org.json closure against them.
                sub.addModule("preprocess", preprocess, Stream.concat(original.stream(), manifests.stream()));
                inner.accept(sub, inherited);
            };
        }
    }
}
