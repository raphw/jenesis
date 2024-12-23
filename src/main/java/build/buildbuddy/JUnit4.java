package build.buildbuddy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class JUnit4 extends Java {

    public JUnit4() {
    }

    public JUnit4(String java) {
        super(java);
    }

    @Override
    protected CompletionStage<List<String>> commands(Executor executor,
                                                     BuildStepContext context,
                                                     Map<String, BuildStepArgument> arguments) throws IOException {
        return CompletableFuture.completedFuture(List.of(
                "org.junit.runner.JUnitCore",
                "sample.SampleTest"));
    }
}
