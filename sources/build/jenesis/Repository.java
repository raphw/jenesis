package build.jenesis;

import module java.base;

@FunctionalInterface
public interface Repository {

    Optional<RepositoryItem> fetch(Executor executor, String coordinate) throws IOException;

    default Repository prepend(Repository repository) {
        return (executor, coordinate) -> {
            Optional<RepositoryItem> candidate = repository.fetch(executor, coordinate);
            return candidate.isPresent() ? candidate : fetch(executor, coordinate);
        };
    }

    default Repository cached(Path folder) {
        if (folder == null) {
            return this;
        }
        ConcurrentMap<String, Path> cache = new ConcurrentHashMap<>();
        return (executor, coordinate) -> {
            try {
                Path target = cache.computeIfAbsent(coordinate, key -> {
                    Path candidate = folder.resolve(URLEncoder.encode(key, StandardCharsets.UTF_8) + ".jar");
                    if (Files.exists(candidate)) {
                        return candidate;
                    }
                    try {
                        RepositoryItem item = fetch(executor, key).orElse(null);
                        if (item == null) {
                            return null;
                        }
                        Path file = item.getFile().orElse(null);
                        if (file != null) {
                            Files.createLink(candidate, file);
                        } else {
                            try (InputStream inputStream = item.toInputStream()) {
                                Files.copy(inputStream, candidate);
                            }
                        }
                        return candidate;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                return target == null ? Optional.empty() : Optional.of(RepositoryItem.ofFile(target));
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        };
    }

    static Repository empty() {
        return (_, _) -> Optional.empty();
    }

    static Repository ofUris(Map<String, URI> uris) {
        return (_, coordinate) -> {
            URI uri = uris.get(coordinate);
            if (uri == null) {
                return Optional.empty();
            } else if (Objects.equals("file", uri.getScheme())) {
                return Optional.of(RepositoryItem.ofFile(Path.of(uri)));
            } else {
                return Optional.of(() -> uri.toURL().openStream());
            }
        };
    }

    static Repository ofFiles(Map<String, Path> files) {
        return (_, coordinate) -> {
            Path file = files.get(coordinate);
            return file == null ? Optional.empty() : Optional.of(RepositoryItem.ofFile(file));
        };
    }

    static Repository files() {
        return (_, coordinate) -> {
            Path file = Paths.get(coordinate);
            return Files.exists(file) ? Optional.of(RepositoryItem.ofFile(file)) : Optional.empty();
        };
    }

    static Map<String, Repository> ofProperties(String suffix,
                                                Iterable<Path> folders,
                                                Function<String, URI> resolver,
                                                Path cache) throws IOException {
        Map<String, Map<String, URI>> artifacts = new HashMap<>();
        for (Path folder : folders) {
            Path file = folder.resolve(suffix);
            if (Files.exists(file)) {
                Properties properties = new SequencedProperties();
                try (Reader reader = Files.newBufferedReader(file)) {
                    properties.load(reader);
                }
                for (String coordinate : properties.stringPropertyNames()) {
                    String location = properties.getProperty(coordinate);
                    if (!location.isEmpty()) {
                        int index = coordinate.indexOf('/');
                        artifacts.computeIfAbsent(
                                coordinate.substring(0, index),
                                _ -> new HashMap<>()).put(coordinate.substring(index + 1), resolver.apply(location));
                    }
                }
            }
        }
        return artifacts.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), Repository.ofUris(entry.getValue()).cached(cache)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    static Map<String, Repository> prepend(Map<String, ? extends Repository> left,
                                           Map<String, ? extends Repository> right) {
        return Stream.concat(left.entrySet().stream(), right.entrySet().stream()).collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue,
                Repository::prepend));
    }
}
