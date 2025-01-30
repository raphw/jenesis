package build.buildbuddy.maven;

import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;

import java.io.IOException;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class MavenPom implements BuildStep {

    private final MavenPomEmitter emitter = new MavenPomEmitter();

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException { // TODO: emit Maven POM.
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
