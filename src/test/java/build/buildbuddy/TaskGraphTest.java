package build.buildbuddy;

import org.junit.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TaskGraphTest {

    private TaskGraph<String, String> taskGraph = new TaskGraph<>((left, right) -> left + right);

    @Test
    public void executes_empty() {
        CompletionStage<String> completion = taskGraph.execute(Runnable::run, CompletableFuture.completedStage("s"));
        assertThat(completion).isCompletedWithValue("s");
    }

    @Test
    public void executes_single() {
        taskGraph.add("1", (executor, state) -> CompletableFuture.completedStage(state + "1"), Set.of());
        CompletionStage<String> completion = taskGraph.execute(Runnable::run, CompletableFuture.completedStage("s"));
        assertThat(completion).isCompletedWithValue("ss1");
    }

    @Test
    public void executes_dependent() {
        taskGraph.add("1", (executor, state) -> CompletableFuture.completedStage(state + "1"), Set.of());
        taskGraph.add("2", (executor, state) -> CompletableFuture.completedStage(state + "2"), Set.of("1"));
        CompletionStage<String> completion = taskGraph.execute(Runnable::run, CompletableFuture.completedStage("s"));
        assertThat(completion).succeedsWithin(Duration.ofSeconds(3)).isEqualTo("ss1ss12");
    }

    @Test
    public void executes_independent() {
        taskGraph.add("1", (executor, state) -> CompletableFuture.completedStage(state + "1"), Set.of());
        taskGraph.add("2", (executor, state) -> CompletableFuture.completedStage(state + "2"), Set.of());
        CompletionStage<String> completion = taskGraph.execute(Runnable::run, CompletableFuture.completedStage("s"));
        assertThat(completion).succeedsWithin(Duration.ofSeconds(3)).isEqualTo("ss1s2");
    }

    @Test
    public void requires_dependency() {
        assertThatThrownBy(() -> taskGraph.add("2",
                (executor, state) -> CompletableFuture.completedStage(state + "2"),
                Set.of("1"))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void requires_unique_identifier() {
        taskGraph.add("1", (executor, state) -> CompletableFuture.completedStage(state + "1"), Set.of());
        assertThatThrownBy(() -> taskGraph.add("1",
                (executor, state) -> CompletableFuture.completedStage(state + "1"),
                Set.of())
        ).isInstanceOf(IllegalArgumentException.class);
    }
}
