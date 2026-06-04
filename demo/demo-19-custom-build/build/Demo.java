package build;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.step.Bind;
import build.jenesis.step.Jar;
import build.jenesis.step.Javac;
import build.jenesis.step.ProcessHandler;

/**
 * A build wired entirely by hand: no {@code Project}, no layout, no
 * multi-project assembler - just sources and steps added directly to a
 * {@code BuildExecutor}. The whole build graph is visible and editable right
 * here, and there is no project template deciding what a build "must" look like.
 *
 * The step that justifies going template-free is {@code generate}: it synthesizes
 * a Java source on the fly. The templated {@code Project} flow assumes every
 * source already exists on disk and follows a fixed compile/jar/test shape; a
 * hand-wired graph can splice in arbitrary work - here, code generation - exactly
 * where it wants and feed it into the compiler like any other source.
 *
 * Run from this directory with:
 *
 *     java build/Demo.java
 *
 * It produces a classpath jar under {@code target/jar/output/artifacts/}. Run it
 * with {@code java -cp <that jar> sample.Sample}.
 */
public class Demo {

    static void main(String[] args) throws Exception {
        BuildExecutor root = BuildExecutor.of(Path.of("target"));

        // The hand-written sources.
        root.addSource("sources", Bind.asSources(), Path.of("sources"));

        // A bespoke step with no analogue in the project templates: it writes a
        // Java source into its own "sources/" output, to be compiled like any
        // other source folder.
        root.addStep("generate", new GenerateSource(
                "Hello from a generated source, compiled by a hand-wired BuildExecutor!"));

        // Compile the hand-written and generated sources together, then package.
        // Javac reads the "sources/" folder of every predecessor, so it sees both.
        root.addStep("compile", new Javac(ProcessHandler.Factory.of()), "sources", "generate");
        root.addStep("jar", new Jar(ProcessHandler.Factory.of(), Jar.Sort.CLASSES), "compile");

        root.execute(args);
    }

    private record GenerateSource(String message) implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            Path directory = Files.createDirectories(context.next().resolve(BuildStep.SOURCES).resolve("sample"));
            Files.writeString(directory.resolve("Generated.java"), """
                    package sample;

                    class Generated {
                        static final String MESSAGE = "%s";
                    }
                    """.formatted(message));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
