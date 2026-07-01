package build;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Project;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.project.AssemblyDescriptor;
import build.jenesis.project.InferredMultiProjectAssembler;
import build.jenesis.project.MultiProjectAssembler;
import build.jenesis.project.ProjectModuleDescriptor;
import build.jenesis.step.JMod;

/**
 * A custom assembler that packages extra, non-class content into a module's
 * `.jmod`, links it into a runtime image with `jlink`, and wraps that runtime in
 * a self-contained application image with `jpackage` - showing the full chain by
 * which a jmod's content reaches a packaged, runnable app.
 *
 * The stock assembler already produces a `.jmod`, a `jlink` runtime, and a
 * `jpackage` image once `jmod`/`jlink`/packaging are enabled. This wrapper only
 * adds the additional input: a `config` step that emits a `jmodconfig/` directory,
 * declared as the module's `content` so the stock `jmod` step depends on it and
 * routes it to `jmod --config`. No jmod/jlink/jpackage wiring is duplicated here.
 *
 * Because the config rides in the `.jmod`'s config section, `jlink` places it into
 * the runtime's `conf/`, and `jpackage` - wired to bundle that very runtime via
 * `--runtime-image` - carries it into the application image. The packaged app then
 * reads it back from its own `<java.home>/conf/`, something a jar cannot do (a
 * jar's embedded resources stay inside the jar). Run it from this directory:
 *
 *     java build/Demo.java
 */
public class Demo {

    static void main(String[] args) throws Exception {
        // jmod, jlink and jpackage are selected by the committed packaging.properties in this
        // directory (jmod=true / jlink=true / jpackage=app-image), which Jenesis reads from
        // the configuration location, so the wrapped stock assembler needs no extra wiring.
        Project project = new Project()
                .assembler(new ConfigJmodAssembler(new InferredMultiProjectAssembler()));

        SequencedMap<String, Path> outputs = project.build("stage");
        Path output = outputs.get("stage/packages");

        String name = "demo.config";
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path launcher;
        if (os.contains("win")) {
            launcher = output.resolve(name).resolve(name + ".exe");
        } else if (os.contains("mac")) {
            launcher = output.resolve(name + ".app").resolve("Contents").resolve("MacOS").resolve(name);
        } else {
            launcher = output.resolve(name).resolve("bin").resolve(name);
        }

        List<String> command = new ArrayList<>();
        command.add(launcher.toString());
        command.addAll(List.of(args));
        System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
    }

    private record ConfigJmodAssembler(MultiProjectAssembler<? super ProjectModuleDescriptor> delegate)
            implements MultiProjectAssembler<ProjectModuleDescriptor> {

        @Override
        public AssemblyDescriptor apply(ProjectModuleDescriptor descriptor,
                                        Map<String, Repository> repositories,
                                        Map<String, Resolver> resolvers) {
            // Declare a config step's output as the module's content, so the stock
            // jmod step (which runs in the build phase) depends on it and routes the
            // emitted config/ directory to jmod --config. Only the build phase is
            // decorated; the later jlink/jpackage phase passes through unchanged.
            return delegate.apply(descriptor.content("config"), repositories, resolvers).mapBuild(inner -> (sub, inherited) -> {
                sub.addStep("config", new GenerateConfig());
                inner.accept(sub, inherited);
            });
        }
    }

    private record GenerateConfig() implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            Path config = Files.createDirectory(context.next().resolve(JMod.CONFIG));
            Files.writeString(config.resolve("app.properties"),
                    "greeting=Configured in a .jmod, linked into the runtime by jlink\n");
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
