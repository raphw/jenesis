package build.buildbuddy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static java.util.Objects.requireNonNull;

public class Dependencies implements BuildStep {

    private final Map<String, Repository> repositories;

    public Dependencies() {
        repositories = Map.of("maven", new MavenRepository());
    }

    public Dependencies(Map<String, Repository> repositories) {
        this.repositories = repositories;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  Map<String, BuildStepArgument> arguments) throws IOException {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (BuildStepArgument result : arguments.values()) {
            for (Path path : result.files().keySet()) {
                if (path.toString().endsWith(".dependencies")) {
                    Properties properties = new Properties();
                    try (InputStream inputStream = Files.newInputStream(result.folder().resolve(path))) {
                        properties.load(inputStream);
                    }
                    for (String dependency : properties.stringPropertyNames()) {
                        String[] segments = dependency.split(":", 2);
                        Repository repository = requireNonNull(
                                repositories.get(segments.length == 1 ? "" : segments[0]),
                                "Could not resolve dependency: " + dependency);
                        CompletableFuture<?> future = new CompletableFuture<>();
                        if (context.previous() != null && Files.exists(context.previous().resolve(dependency))) {
                            Files.copy(context.previous().resolve(dependency), context.next().resolve(dependency));
                        } else {
                            String[] expectation = properties.getProperty(dependency).split(":", 2);
                            executor.execute(() -> {
                                try (
                                        DigestInputStream inputStream = new DigestInputStream(
                                                repository.fetch(segments[segments.length == 1 ? 0 : 1]),
                                                MessageDigest.getInstance(expectation.length == 1 ? "SHA256" : expectation[0]));
                                        OutputStream outputStream = Files.newOutputStream(context.next().resolve(dependency))
                                ) {
                                    inputStream.transferTo(outputStream);
                                    String digest = Base64.getEncoder().encodeToString(inputStream.getMessageDigest().digest());
                                    if (!digest.equals(expectation[expectation.length == 1 ? 0 : 1])) {
                                        Files.delete(context.next().resolve(dependency));
                                        throw new IllegalStateException("Mismatched digest for " + dependency);
                                    }
                                    future.complete(null);
                                } catch (Throwable t) {
                                    future.completeExceptionally(t);
                                }
                            });
                        }
                        futures.add(future);
                    }
                }
            }
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> new BuildStepResult(true));
    }
}
