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
        boolean verbose = Boolean.getBoolean("jenesis.verbose");
        ConcurrentMap<String, Path> cache = new ConcurrentHashMap<>();
        return (executor, coordinate) -> {
            try {
                Path candidate = folder.resolve(URLEncoder.encode(coordinate, StandardCharsets.UTF_8) + ".jar");
                boolean preexisting = Files.exists(candidate);
                Path target = cache.computeIfAbsent(coordinate, key -> {
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
                if (verbose && preexisting && target != null) {
                    System.out.printf("%s%-11s%s %s%n",
                            BuildExecutorCallback.YELLOW, "[FETCHED]", BuildExecutorCallback.RESET,
                            target.toAbsolutePath().toUri());
                }
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
        boolean verbose = Boolean.getBoolean("jenesis.verbose");
        return (_, coordinate) -> {
            URI candidate = uris.get(coordinate);
            if (candidate == null) {
                int slash = coordinate.lastIndexOf('/');
                if (slash > 0) {
                    URI base = uris.get(coordinate.substring(0, slash));
                    if (base != null) {
                        candidate = substituteMavenVersion(base, coordinate.substring(slash + 1)).orElse(base);
                    }
                }
            }
            if (candidate == null) {
                return Optional.empty();
            }
            URI uri = candidate;
            if (verbose) {
                System.out.printf("%s%-11s%s %s%n",
                        BuildExecutorCallback.YELLOW, "[FETCHED]", BuildExecutorCallback.RESET, uri);
            }
            if (Objects.equals("file", uri.getScheme())) {
                return Optional.of(RepositoryItem.ofFile(Path.of(uri)));
            } else {
                return Optional.of(() -> uri.toURL().openStream());
            }
        };
    }

    /**
     * Replaces the version in a Maven-conventional URI of the form
     * {@code .../<artifactId>/<version>/<artifactId>-<version>[-<classifier>].<ext>}.
     * Returns empty if the URI does not match that layout, leaving the caller free to fall back to the
     * original URI.
     */
    static Optional<URI> substituteMavenVersion(URI uri, String version) {
        String path = uri.getPath();
        if (path == null) {
            return Optional.empty();
        }
        int last = path.lastIndexOf('/');
        if (last <= 0) {
            return Optional.empty();
        }
        int versionStart = path.lastIndexOf('/', last - 1);
        if (versionStart <= 0) {
            return Optional.empty();
        }
        int artifactStart = path.lastIndexOf('/', versionStart - 1);
        if (artifactStart < 0) {
            return Optional.empty();
        }
        String artifactId = path.substring(artifactStart + 1, versionStart);
        String existingVersion = path.substring(versionStart + 1, last);
        String filename = path.substring(last + 1);
        String prefix = artifactId + "-" + existingVersion;
        if (!filename.startsWith(prefix)) {
            return Optional.empty();
        }
        String tail = filename.substring(prefix.length());
        int dot = tail.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String classifier = tail.substring(0, dot);
        if (!classifier.isEmpty() && !classifier.startsWith("-")) {
            return Optional.empty();
        }
        String extension = tail.substring(dot);
        String newPath = path.substring(0, versionStart + 1)
                + version
                + "/" + artifactId + "-" + version + classifier + extension;
        try {
            return Optional.of(new URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    uri.getHost(),
                    uri.getPort(),
                    newPath,
                    uri.getQuery(),
                    uri.getFragment()));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
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
                                                BiFunction<Path, String, URI> resolver,
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
                                _ -> new HashMap<>()).put(coordinate.substring(index + 1), resolver.apply(folder, location));
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
