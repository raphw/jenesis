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

    private final String algorithm;
    private final Map<String, Repository> repositories;

    public Dependencies() {
        algorithm = "SHA256";
        repositories = Map.of("maven", new MavenRepository());
    }

    public Dependencies(String algorithm, Map<String, Repository> repositories) {
        this.algorithm = algorithm;
        this.repositories = repositories;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  Path previous,
                                                  Path next,
                                                  Map<String, BuildStepArgument> arguments) throws IOException {
        List<CompletionStage<Boolean>> stages = new ArrayList<>();
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
                        CompletableFuture<Boolean> future = new CompletableFuture<>();
                        executor.execute(() -> {
                            try (
                                    DigestInputStream inputStream = new DigestInputStream(
                                            repository.download(segments[segments.length == 1 ? 0 : 1]),
                                            MessageDigest.getInstance(algorithm));
                                    OutputStream outputStream = Files.newOutputStream(next.resolve(dependency));
                            ) {
                                inputStream.transferTo(outputStream);
                                String digest = Base64.getEncoder().encodeToString(inputStream.getMessageDigest().digest());
                                if (!digest.equals(properties.getProperty(dependency))) {
                                    Files.delete(next.resolve(dependency));
                                    throw new IllegalStateException("Mismatched digest for " + dependency);
                                }
                                future.complete(true);
                            } catch (Throwable t) {
                                future.completeExceptionally(t);
                            }
                        });
                        stages.add(future);
                    }
                }
            }
        }
        // TODO: proper completion handling.
        return stages.stream()
                .reduce((left, right) -> left.thenCombine(right, Boolean::logicalAnd))
                .map(stage -> stage.thenApply(BuildStepResult::new))
                .orElseGet(() -> CompletableFuture.completedStage(new BuildStepResult(true)));
    }
}
