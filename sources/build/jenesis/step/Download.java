package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.SequencedProperties;

public class Download implements DependencyProcessingBuildStep {

    private final transient Map<String, Repository> repositories;
    private final boolean strictPinning;

    public Download(Map<String, Repository> repositories) {
        this(repositories, false);
    }

    public Download(Map<String, Repository> repositories, boolean strictPinning) {
        this.repositories = repositories;
        this.strictPinning = strictPinning;
    }

    @Override
    public CompletionStage<SequencedProperties> transform(Executor executor,
                                                          BuildStepContext context,
                                                          SequencedMap<String, BuildStepArgument> arguments,
                                                          SequencedMap<String, SequencedMap<String, String>> groups,
                                                          SequencedMap<String, SequencedMap<String, String>> versions)
            throws IOException {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        Path libs = Files.createDirectory(context.next().resolve(DEPENDENCIES));
        for (Map.Entry<String, SequencedMap<String, String>> group : groups.entrySet()) {
            Repository repository = repositories.getOrDefault(group.getKey(), Repository.empty());
            for (Map.Entry<String, String> entry : group.getValue().entrySet()) {
                String dependency = group.getKey() + "/" + entry.getKey(), name = dependency.replace('/', '-') + ".jar";
                Path previous = context.previous() == null ? null : context.previous().resolve(DEPENDENCIES + name);
                if (entry.getValue().isEmpty()) {
                    if (previous != null && Files.exists(previous) && !strictPinning) {
                        BuildStep.linkOrCopy(libs.resolve(name), previous);
                        continue;
                    }
                    CompletableFuture<?> future = new CompletableFuture<>();
                    executor.execute(() -> {
                        try {
                            RepositoryItem source = repository.fetch(executor, entry.getKey()).orElseThrow(
                                    () -> new IllegalStateException("Unresolved: " + dependency));
                            if (strictPinning && !source.internal()) {
                                throw new IllegalStateException(
                                        "No checksum pinned for " + dependency + " (strict pinning is enabled)");
                            }
                            if (previous != null && Files.exists(previous)) {
                                BuildStep.linkOrCopy(libs.resolve(name), previous);
                                future.complete(null);
                                return;
                            }
                            Path file = source.file().orElse(null);
                            if (file == null) {
                                try (InputStream inputStream = source.toInputStream()) {
                                    Files.copy(inputStream, libs.resolve(name));
                                }
                            } else {
                                BuildStep.linkOrCopy(context.next().resolve(DEPENDENCIES + name), file);
                            }
                            future.complete(null);
                        } catch (Throwable t) {
                            future.completeExceptionally(new RuntimeException(
                                    "Failed to fetch " + dependency,
                                    t));
                        }
                    });
                    futures.add(future);
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
                            BuildStep.linkOrCopy(libs.resolve(name), previous);
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
                            Path file = source.file().orElse(null);
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
                                    BuildStep.linkOrCopy(context.next().resolve(DEPENDENCIES + name), file);
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
                .thenApply(_ -> null);
    }

    private static boolean validateFile(MessageDigest digest, Path file, String expected) throws IOException {
        try (FileChannel channel = FileChannel.open(file)) {
            digest.update(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()));
        }
        return Arrays.equals(digest.digest(), HexFormat.of().parseHex(expected));
    }
}
