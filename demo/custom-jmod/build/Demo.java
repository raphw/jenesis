package build;

import module java.base;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Project;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.project.JavaMultiProjectAssembler;
import build.jenesis.project.MultiProjectAssembler;
import build.jenesis.project.ProjectModuleDescriptor;
import build.jenesis.step.JMod;

/**
 * A custom assembler that packages extra, non-class content into a module's
 * `.jmod` and links it into a runtime image with `jlink` - showing why the jmod
 * form is worth more than a jar for a runtime image.
 *
 * The stock assembler already produces a `.jmod` and a `jlink` runtime once
 * `jmod`/`jlink` are enabled. This wrapper only adds the additional input: a
 * `config` step that emits a `jmodconfig/` directory, declared as the module's
 * `content` so the stock `jmod` step depends on it and routes it to `jmod
 * --config`. No jmod/jlink wiring is duplicated here.
 *
 * Because the config rides in the `.jmod`'s config section, `jlink` places it into
 * the produced runtime's `conf/` directory - something a jar cannot do (a jar's
 * embedded resources stay inside the jar). Run it from this directory:
 *
 *     java build/Demo.java
 */
public class Demo {

    static void main(String[] args) throws Exception {
        Project project = new Project()
                .assembler(new ConfigJmodAssembler(new JavaMultiProjectAssembler().jmod(true).jlink(true)))
                .resolveProperties();
        project.build(args);

        Path conf;
        try (Stream<Path> walk = Files.walk(project.target())) {
            conf = walk.filter(path -> path.endsWith(Path.of("runtime", "conf", "app.properties")))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("No jlink runtime with bundled config was produced"));
        }
        System.out.println("jlink placed the jmod's config into the runtime at " + conf + ":");
        System.out.println(Files.readString(conf).strip());
    }

    private record ConfigJmodAssembler(MultiProjectAssembler<? super ProjectModuleDescriptor> delegate)
            implements MultiProjectAssembler<ProjectModuleDescriptor> {

        @Override
        public BuildExecutorModule apply(ProjectModuleDescriptor descriptor,
                                         Map<String, Repository> repositories,
                                         Map<String, Resolver> resolvers) {
            BuildExecutorModule inner = delegate.apply(descriptor.withContent("config"), repositories, resolvers);
            return (sub, inherited) -> {
                sub.addStep("config", new GenerateConfig());
                inner.accept(sub, inherited);
            };
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
