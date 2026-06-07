package build.jenesis.step;

import module java.base;
import build.jenesis.Pinning;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;

public class Download implements BuildStep {

    private final transient Map<String, Repository> repositories;
    private final Pinning pinning;

    public Download(Map<String, Repository> repositories) {
        this(repositories, null);
    }

    private Download(Map<String, Repository> repositories, Pinning pinning) {
        this.repositories = repositories;
        this.pinning = pinning;
    }

    public Download pinning(Pinning pinning) {
        return new Download(repositories, pinning);
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(argument -> argument.hasChanged(Path.of(TRANSITIVES)));
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedMap<String, String> merged = new LinkedHashMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path file = argument.folder().resolve(TRANSITIVES);
            if (!Files.exists(file)) {
                continue;
            }
            SequencedProperties properties = SequencedProperties.ofFiles(file);
            for (String key : properties.stringPropertyNames()) {
                merged.merge(key, properties.getProperty(key), (left, right) -> left.isEmpty() ? right : left);
            }
        }
        List<CompletableFuture<?>> futures = new ArrayList<>();
        Path libs = Files.createDirectory(context.next().resolve(DEPENDENCIES));
        SequencedProperties index = new SequencedProperties();
        SequencedMap<String, String[]> coalesced = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            String[] parts = split(entry.getKey());
            if (parts == null) {
                continue;
            }
            String repo = parts[1], coordinate = parts[2];
            String dependency = repo + "/" + coordinate, name = dependency.replace('/', '-') + ".jar";
            index.setProperty(entry.getKey(), DEPENDENCIES + name);
            String[] existing = coalesced.get(name);
            if (existing == null) {
                coalesced.put(name, new String[] {repo, coordinate, dependency, entry.getValue()});
            } else if (!entry.getValue().isEmpty()) {
                if (existing[3].isEmpty()) {
                    existing[3] = entry.getValue();
                } else if (!existing[3].equals(entry.getValue())) {
                    throw new IllegalStateException("Conflicting checksums pinned for " + dependency
                            + ": " + existing[3] + " and " + entry.getValue());
                }
            }
        }
        for (String[] parts : coalesced.values()) {
            String repo = parts[0], coordinate = parts[1], dependency = parts[2], name = dependency.replace('/', '-') + ".jar";
            Repository repository = repositories.getOrDefault(Resolver.base(repo), Repository.empty());
            Path previous = context.previous() == null ? null : context.previous().resolve(DEPENDENCIES + name);
            String value = pinning == Pinning.IGNORE ? "" : parts[3];
            if (value.isEmpty()) {
                if (previous != null && Files.exists(previous) && pinning != Pinning.STRICT) {
                    BuildStep.linkOrCopy(libs.resolve(name), previous);
                    continue;
                }
                CompletableFuture<?> future = new CompletableFuture<>();
                executor.execute(() -> {
                    try {
                        RepositoryItem source = repository.fetch(executor, coordinate).orElseThrow(
                                () -> new IllegalStateException("Unresolved: " + dependency));
                        if (pinning == Pinning.STRICT && !source.internal()) {
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
                            BuildStep.linkOrCopy(libs.resolve(name), file);
                        }
                        future.complete(null);
                    } catch (Throwable t) {
                        future.completeExceptionally(new RuntimeException("Failed to fetch " + dependency, t));
                    }
                });
                futures.add(future);
            } else {
                int algorithm = value.indexOf('/');
                MessageDigest digest;
                try {
                    digest = MessageDigest.getInstance(value.substring(0, algorithm));
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
                String checksum = value.substring(algorithm + 1);
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
                        RepositoryItem source = repository.fetch(executor, coordinate).orElseThrow(
                                () -> new IllegalStateException("Unresolved: " + dependency));
                        Path file = source.file().orElse(null);
                        if (file == null) {
                            try (DigestInputStream inputStream = new DigestInputStream(source.toInputStream(), digest)) {
                                Files.copy(inputStream, libs.resolve(name));
                                if (!Arrays.equals(
                                        inputStream.getMessageDigest().digest(),
                                        HexFormat.of().parseHex(checksum))) {
                                    throw new IllegalStateException("Mismatched digest for " + dependency);
                                }
                            }
                        } else {
                            if (validateFile(digest, file, checksum)) {
                                BuildStep.linkOrCopy(libs.resolve(name), file);
                            } else {
                                throw new IllegalStateException("Mismatched digest for " + dependency);
                            }
                        }
                        future.complete(null);
                    } catch (Throwable t) {
                        future.completeExceptionally(new RuntimeException("Failed to fetch " + dependency, t));
                    }
                });
                futures.add(future);
            }
        }
        index.store(context.next().resolve(DEPENDENCY_INDEX));
        return CompletableFuture
                .allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(_ -> new BuildStepResult(true));
    }

    private static String[] split(String key) {
        int first = key.indexOf('/');
        if (first < 1) {
            return null;
        }
        int second = key.indexOf('/', first + 1);
        if (second < 0) {
            return null;
        }
        return new String[] {key.substring(0, first), key.substring(first + 1, second), key.substring(second + 1)};
    }

    private static boolean validateFile(MessageDigest digest, Path file, String expected) throws IOException {
        try (FileChannel channel = FileChannel.open(file)) {
            digest.update(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()));
        }
        return Arrays.equals(digest.digest(), HexFormat.of().parseHex(expected));
    }
}
