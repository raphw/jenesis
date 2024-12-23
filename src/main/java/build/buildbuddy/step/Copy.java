package build.buildbuddy.step;

import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Copy implements BuildStep {

    private final Map<Path, URI> copies;
    private final Duration cached;

    public Copy(Map<Path, URI> copies, Duration cached) {
        this.copies = copies;
        this.cached = cached;
    }

    @Override
    public boolean isAlwaysRun() {
        return true;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  Map<String, BuildStepArgument> arguments) throws IOException {
        Instant expiry = cached == null ? null : Instant.now().minus(cached);
        boolean altered = false;
        for (Map.Entry<Path, URI> entry : copies.entrySet()) {
            Path next = context.next().resolve(entry.getKey());
            if (!next.getParent().equals(context.next())) {
                Files.createDirectories(next.getParent());
            }
            if (context.previous() != null) {
                Path previous = context.previous().resolve(entry.getKey());
                if (expiry != null && Files.exists(previous) && Files.readAttributes(
                        previous,
                        BasicFileAttributes.class).creationTime().toInstant().isAfter(expiry)) {
                    Files.createLink(next, previous);
                    continue;
                }
            }
            try (InputStream inputStream = entry.getValue().toURL().openStream()) {
                Files.copy(inputStream, next);
            }
            altered = true;
        }
        return CompletableFuture.completedStage(new BuildStepResult(altered || context.previous() == null));
    }
}
