package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.BuildStepResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class BuildStepHashFunctionTest {

    @Test
    public void hashes_serializable_step_by_class_and_fields() throws IOException {
        BuildStepHashFunction hash = BuildStepHashFunction.ofSerializationDigest("MD5");
        byte[] first = hash.hash(new ConfigurableStep("foo"));
        byte[] second = hash.hash(new ConfigurableStep("foo"));
        byte[] different = hash.hash(new ConfigurableStep("bar"));
        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(different);
    }

    @Test
    public void hashes_path_fields_independent_of_separator() throws IOException {
        BuildStepHashFunction hash = BuildStepHashFunction.ofSerializationDigest("MD5");
        byte[] forwardSlash = hash.hash(new PathStep(Path.of("nested", "file")));
        byte[] backSlash = hash.hash(new PathStep(Path.of("nested\\file")));
        assertThat(forwardSlash).isEqualTo(backSlash);
    }

    @Test
    public void throws_for_non_serializable_step() {
        BuildStepHashFunction hash = BuildStepHashFunction.ofSerializationDigest("MD5");
        BuildStep step = new NonSerializableStep();
        assertThatThrownBy(() -> hash.hash(step)).isInstanceOf(NotSerializableException.class);
    }

    private record ConfigurableStep(String value) implements BuildStep {
        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments) {
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private record PathStep(Path path) implements BuildStep {
        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments) {
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }

    private class NonSerializableStep implements BuildStep {
        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments) {
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
