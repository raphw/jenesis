package build;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.Project;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.Resolver;
import build.jenesis.module.JenesisModuleRepository;
import build.jenesis.module.ModularJarResolver;
import build.jenesis.project.ExternalModule;
import build.jenesis.project.InternalModule;
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
 * module: it compiles and jars {@code plugin/} into a nested {@code target/}
 * folder (separate from this build's own {@code target/}) without running it,
 * then serves the resulting jar under a custom coordinate. The custom
 * {@code Project} wires that coordinate as an {@code ExternalModule}.
 *
 * Run from this directory with:
 *
 *     java build/Demo.java
 */
public class Demo {

    static void main(String[] args) throws Exception {
        // Stage the build module: compile and jar plugin/ with InternalModule
        // into a nested target folder. Selecting the jar step stops before the
        // module would run, so staging only produces its artifact.
        Path stagingTarget = Path.of("target", "internal");
        Files.createDirectories(stagingTarget.getParent());
        BuildExecutor staging = BuildExecutor.of(stagingTarget);
        staging.addModule("plugin", new InternalModule("module", "tool", Path.of("plugin")));
        staging.execute("plugin/java/artifacts");
        Path pluginJar;
        try (Stream<Path> walk = Files.walk(stagingTarget)) {
            pluginJar = walk
                    .filter(path -> path.toString().contains("java/artifacts") && path.toString().endsWith(".jar"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Staging did not produce a plugin jar"));
        }

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

        new Project()
                .assembler(new PreprocessingAssembler(
                        new JavaMultiProjectAssembler(),
                        Map.of("module", repository),
                        Map.of("module", new ModularJarResolver(true))))
                .build(args);
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
