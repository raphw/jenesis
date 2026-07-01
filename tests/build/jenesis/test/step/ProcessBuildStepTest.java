package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.step.ProcessBuildStep;
import build.jenesis.step.ProcessHandler;

import static org.assertj.core.api.Assertions.assertThat;

public class ProcessBuildStepTest {

    @BeforeEach
    @AfterEach
    public void clear() {
        System.clearProperty("jenesis.print.process");
        System.clearProperty("jenesis.print.probe");
    }

    @Test
    public void the_command_specific_property_enables_streaming() {
        System.setProperty("jenesis.print.probe", "true");
        assertThat(new Probe().streams()).isTrue();
    }

    @Test
    public void the_generic_property_enables_streaming() {
        System.setProperty("jenesis.print.process", "true");
        assertThat(new Probe().streams()).isTrue();
    }

    @Test
    public void the_command_specific_property_takes_precedence_over_the_generic_one() {
        System.setProperty("jenesis.print.process", "true");
        System.setProperty("jenesis.print.probe", "false");
        assertThat(new Probe().streams()).isFalse();
    }

    @Test
    public void an_explicit_value_overrides_the_resolved_property() {
        assertThat(new Probe(true).streams()).isTrue();
        System.setProperty("jenesis.print.process", "true");
        assertThat(new Probe(false).streams()).isFalse();
    }

    private static final class Probe extends ProcessBuildStep {

        private static final ProcessHandler HANDLER = ProcessHandler.OfTool.of(new ToolProvider() {
            @Override
            public String name() {
                return "probe";
            }

            @Override
            public int run(PrintWriter out, PrintWriter err, String... arguments) {
                return 0;
            }
        }).apply(List.of());

        private Probe() {
            super("probe", arguments -> HANDLER);
        }

        private Probe(boolean verbose) {
            super("probe", arguments -> HANDLER, verbose);
        }

        private boolean streams() {
            PrintStream original = System.out;
            System.setOut(new PrintStream(OutputStream.nullOutputStream()));
            try {
                return tee(Runnable::run, HANDLER) != null;
            } finally {
                System.setOut(original);
            }
        }

        @Override
        protected CompletionStage<List<String>> process(Executor executor,
                                                        BuildStepContext context,
                                                        SequencedMap<String, BuildStepArgument> arguments,
                                                        SequencedMap<String, SequencedMap<String, String>> properties) {
            return CompletableFuture.completedStage(List.of());
        }
    }
}
