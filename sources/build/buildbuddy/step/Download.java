package build.buildbuddy.step;

import build.buildbuddy.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static java.util.Objects.requireNonNull;

public class Download implements DependencyTransformingBuildStep {

    private final Map<String, Repository> repositories;

    public Download(Map<String, Repository> repositories) {
        this.repositories = repositories;
    }

    @Override
    public CompletionStage<Properties> transform(Executor executor,
                                                 BuildStepContext context,
                                                 SequencedMap<String, BuildStepArgument> arguments,
                                                 SequencedMap<String, SequencedMap<String, String>> groups)
            throws IOException {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        Properties properties = new SequencedProperties();
        Path libs = Files.createDirectory(context.next().resolve(ARTIFACTS));
        for (Map.Entry<String, SequencedMap<String, String>> group : groups.entrySet()) {
            Repository repository = repositories.getOrDefault(group.getKey(), Repository.empty());
            for (Map.Entry<String, String> entry : group.getValue().entrySet()) {
                String dependency = group.getKey() + "/" + entry.getKey(), name = dependency.replace('/', '-') + ".jar";
                Path previous = context.previous() == null ? null : context.previous().resolve(ARTIFACTS + name);
                if (entry.getValue().isEmpty()) {
                    if (previous != null && Files.exists(previous)) {
                        Files.createLink(libs.resolve(name), previous);
                    } else {
                        CompletableFuture<?> future = new CompletableFuture<>();
                        executor.execute(() -> {
                            try {
                                RepositoryItem source = repository.fetch(executor, entry.getKey()).orElseThrow(
                                        () -> new IllegalStateException("Unresolved: " + dependency));
                                Path file = source.getFile().orElse(null);
                                if (file == null) {
                                    try (InputStream inputStream = source.toInputStream()) {
                                        Files.copy(inputStream, libs.resolve(name));
                                    }
                                } else {
                                    Files.createLink(context.next().resolve(ARTIFACTS + name), file);
                                }
                                future.complete(null);
                            } catch (Throwable t) {
                                future.completeExceptionally(new RuntimeException(
                                        "Failed to fetch " + dependency,
                                        t));
                            }
                        });
                        futures.add(future);
                    }
                } else {
                    int algorithm = entry.getValue().indexOf('/');
                    MessageDigest digest;
                    try {
                        digest = MessageDigest.getInstance(entry.getValue().substring(0, algorithm));
                    } catch (NoSuchAlgorithmException e) {
                        throw new IllegalStateException(e);
                    }
                    String checksum = entry.getValue().substring(algorithm + 1);
                    if (previous != null && Files.exists(previous)) {
                        if (validateFile(digest, previous, checksum)) {
                            Files.createLink(libs.resolve(name), previous);
                            continue;
                        } else {
                            digest.reset();
                        }
                    }
                    CompletableFuture<?> future = new CompletableFuture<>();
                    executor.execute(() -> {
                        try {
                            RepositoryItem source = repository.fetch(executor, entry.getKey()).orElseThrow(
                                    () -> new IllegalStateException("Unresolved: " + dependency));
                            Path file = source.getFile().orElse(null);
                            if (file == null) {
                                try (DigestInputStream inputStream = new DigestInputStream(
                                        source.toInputStream(),
                                        digest)) {
                                    Files.copy(inputStream, libs.resolve(name));
                                    if (!Arrays.equals(
                                            inputStream.getMessageDigest().digest(),
                                            HexFormat.of().parseHex(checksum))) {
                                        throw new IllegalStateException("Mismatched digest for " + dependency);
                                    }
                                }
                            } else {
                                if (validateFile(digest, file, checksum)) {
                                    Files.createLink(context.next().resolve(ARTIFACTS + name), file);
                                } else {
                                    throw new IllegalStateException("Mismatched digest for " + dependency);
                                }
                            }
                            future.complete(null);
                        } catch (Throwable t) {
                            future.completeExceptionally(new RuntimeException(
                                    "Failed to fetch " + dependency,
                                    t));
                        }
                    });
                    futures.add(future);
                }
            }
        }
        return CompletableFuture
                .allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(_ -> properties);
    }

    private static boolean validateFile(MessageDigest digest, Path file, String expected) throws IOException {
        try (FileChannel channel = FileChannel.open(file)) {
            digest.update(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()));
        }
        return Arrays.equals(digest.digest(), HexFormat.of().parseHex(expected));
    }
}
