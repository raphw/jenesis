package build.jenesis.test;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.BuildStepResult;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class BuildStepHashFunctionTest {

    @Test
    public void hashes_serializable_step_by_class_and_fields() throws IOException {
        BuildStepHashFunction hash = BuildStepHashFunction.ofDigest("MD5");
        byte[] first = hash.hash(new ConfigurableStep("foo"));
        byte[] second = hash.hash(new ConfigurableStep("foo"));
        byte[] different = hash.hash(new ConfigurableStep("bar"));
        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(different);
    }

    @Test
    public void falls_back_to_stable_empty_hash_for_non_serializable_step() throws IOException {
        BuildStepHashFunction hash = BuildStepHashFunction.ofDigest("MD5");
        BuildStep step = (_, _, _) -> CompletableFuture.completedStage(new BuildStepResult(true));
        byte[] first = hash.hash(step);
        byte[] second = hash.hash(step);
        assertThat(first).isEqualTo(second);
    }

    private record ConfigurableStep(String value) implements BuildStep {
        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments) {
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
