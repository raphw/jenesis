package build.jenesis;

import module java.base;

@FunctionalInterface
public interface Resolver extends Serializable {

    record Resolved(Path file, String checksum, boolean internal) implements Serializable {
    }

    SequencedMap<String, Resolved> dependencies(Executor executor,
                                                String prefix,
                                                Map<String, Repository> repositories,
                                                SequencedMap<String, SequencedSet<String>> coordinates,
                                                SequencedMap<String, String> versions,
                                                DependencyScope scope,
                                                ResolutionListener listener) throws IOException;

    default SequencedMap<String, Resolved> dependencies(Executor executor,
                                                        String prefix,
                                                        Map<String, Repository> repositories,
                                                        SequencedMap<String, SequencedSet<String>> coordinates,
                                                        SequencedMap<String, String> versions,
                                                        DependencyScope scope) throws IOException {
        return dependencies(executor, prefix, repositories, coordinates, versions, scope, null);
    }

    default SequencedSet<String> managedPrefixes() {
        return Collections.emptyNavigableSet();
    }

    static String base(String prefix) {
        int at = prefix.indexOf('@');
        return at < 0 ? prefix : prefix.substring(0, at);
    }

    static Resolved materialize(Executor executor,
                                Repository repository,
                                String coordinate,
                                String checksum) throws IOException {
        RepositoryItem item = repository.fetch(executor, coordinate)
                .orElseThrow(() -> new IllegalStateException("Unresolved: " + coordinate));
        Path file = item.file().orElse(null);
        if (file == null) {
            throw new IllegalStateException("Repository did not materialize a file for " + coordinate);
        }
        if (checksum != null && !checksum.isEmpty()) {
            validate(file, checksum, coordinate);
        }
        return new Resolved(file, checksum == null ? "" : checksum, item.internal());
    }

    static void validate(Path file, String checksum, String coordinate) throws IOException {
        int slash = checksum.indexOf('/');
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(checksum.substring(0, slash));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (FileChannel channel = FileChannel.open(file)) {
            digest.update(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()));
        }
        if (!Arrays.equals(digest.digest(), HexFormat.of().parseHex(checksum.substring(slash + 1)))) {
            throw new IllegalStateException("Mismatched digest for " + coordinate);
        }
    }

    static SequencedMap<String, Resolved> materializeAll(Executor executor,
                                                        Map<String, Repository> repositories,
                                                        String prefix,
                                                        SequencedMap<String, String> resolved) throws IOException {
        Repository repository = repositories.getOrDefault(base(prefix), Repository.empty());
        Map<String, Resolved> results = new ConcurrentHashMap<>();
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (Map.Entry<String, String> entry : resolved.entrySet()) {
            String coordinate = entry.getKey();
            String fetch = coordinate.startsWith(prefix + "/")
                    ? coordinate.substring(prefix.length() + 1)
                    : coordinate;
            String checksum = entry.getValue();
            CompletableFuture<?> future = new CompletableFuture<>();
            executor.execute(() -> {
                try {
                    results.put(coordinate, materialize(executor, repository, fetch, checksum.isEmpty() ? null : checksum));
                    future.complete(null);
                } catch (Throwable t) {
                    future.completeExceptionally(new RuntimeException("Failed to fetch " + coordinate, t));
                }
            });
            futures.add(future);
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        SequencedMap<String, Resolved> materialized = new LinkedHashMap<>();
        for (String coordinate : resolved.sequencedKeySet()) {
            materialized.put(coordinate, results.get(coordinate));
        }
        return materialized;
    }

    static Resolver identity() {
        return (executor, prefix, repositories, coordinates, _, _, _) -> {
            SequencedMap<String, String> resolved = new LinkedHashMap<>();
            coordinates.sequencedKeySet().forEach(coordinate -> resolved.put(prefix + "/" + coordinate, ""));
            return materializeAll(executor, repositories, prefix, resolved);
        };
    }
}
