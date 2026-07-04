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
        return cached(folder, false);
    }

    default Repository materialized(Path folder) {
        return cached(folder, true);
    }

    private Repository cached(Path folder, boolean snapshot) {
        if (folder == null) {
            return this;
        }
        boolean verbose = Boolean.getBoolean("jenesis.print.fetch");
        return cached(folder, snapshot, verbose ? target -> System.out.printf("%s%-11s%s %s%n",
                BuildExecutorCallback.YELLOW,
                "[FETCHED]",
                BuildExecutorCallback.RESET,
                target.toAbsolutePath().toUri()) : _ -> {
        });
    }

    default Repository cached(Path folder, Consumer<Path> callback) {
        return cached(folder, false, callback);
    }

    private Repository cached(Path folder, boolean snapshot, Consumer<Path> callback) {
        if (folder == null) {
            return this;
        }
        ConcurrentMap<String, Path> cache = new ConcurrentHashMap<>();
        Set<String> internal = ConcurrentHashMap.newKeySet();
        return (executor, coordinate) -> {
            try {
                Path candidate = folder.resolve(BuildExecutorModule.encode(coordinate) + ".jar");
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
                        Path file = item.file().orElse(null);
                        if (file != null && (item.internal() || !snapshot && item.local())) {
                            // A cache references a local item in place so a republished artifact is picked
                            // up on the next resolution, while a snapshot links it into the folder so a
                            // build output stays deterministic and copyable; the local flag is not
                            // propagated, so an outer snapshot still materializes a cached item. Internal
                            // items always stay in place and propagate to keep their pinning exemption.
                            if (item.internal()) {
                                internal.add(key);
                            }
                            return file;
                        }
                        if (file != null) {
                            BuildStep.linkOrCopy(candidate, file);
                        } else {
                            Path temporary = Files.createTempFile(candidate.getParent(), "fetch", ".jar");
                            try (InputStream inputStream = item.toInputStream()) {
                                Files.copy(inputStream, temporary, StandardCopyOption.REPLACE_EXISTING);
                            } catch (Throwable t) {
                                Files.deleteIfExists(temporary);
                                throw t;
                            }
                            Files.move(temporary, candidate, StandardCopyOption.ATOMIC_MOVE);
                        }
                        return candidate;
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                if (preexisting && target != null) {
                    callback.accept(target);
                }
                return target == null
                        ? Optional.empty()
                        : Optional.of(RepositoryItem.ofFile(target, internal.contains(coordinate)));
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        };
    }

    static Repository empty() {
        return (_, _) -> Optional.empty();
    }

    static InputStream open(URI uri, String token) throws IOException {
        boolean insecure = Boolean.getBoolean("jenesis.repository.insecure");
        URI current = uri;
        for (int redirect = 0; redirect < 8; redirect++) {
            String scheme = current.getScheme();
            if (scheme != null && !scheme.equals("https") && !scheme.equals("file") && !insecure) {
                throw new IllegalStateException("Refusing to fetch over insecure scheme '"
                        + scheme
                        + "': "
                        + current
                        + " (set -Djenesis.repository.insecure=true to allow plaintext repositories)");
            }
            URLConnection connection = current.toURL().openConnection();
            if (!(connection instanceof HttpURLConnection http)) {
                return connection.getInputStream();
            }
            http.setInstanceFollowRedirects(false);
            http.setRequestProperty("User-Agent", "Jenesis");
            // Redirects are followed by hand so the credential is only ever sent to the
            // origin it was configured for, never to whatever host a redirect points at.
            if (token != null && sameOrigin(uri, current)) {
                http.setRequestProperty("Authorization", token);
            }
            int status = http.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = http.getHeaderField("Location");
                if (location != null) {
                    http.getInputStream().close();
                    current = current.resolve(location);
                    continue;
                }
            }
            return http.getInputStream();
        }
        throw new IOException("Exceeded redirect limit fetching " + uri);
    }

    private static boolean sameOrigin(URI left, URI right) {
        return Objects.equals(left.getScheme(), right.getScheme())
                && left.getHost() != null
                && left.getHost().equalsIgnoreCase(right.getHost())
                && left.getPort() == right.getPort();
    }

    static Repository ofUris(Map<String, URI> uris) {
        return ofUris(uris, null);
    }

    static <F extends BiFunction<URI, String, Optional<URI>> & Serializable> Repository ofUris(
            Map<String, URI> uris,
            F versionResolver) {
        boolean verbose = Boolean.getBoolean("jenesis.print.fetch");
        return ofUris(uris, versionResolver, verbose ? uri -> System.out.printf("%s%-11s%s %s%n",
                BuildExecutorCallback.YELLOW,
                "[FETCHED]",
                BuildExecutorCallback.RESET,
                uri) : _ -> {
        });
    }

    static <F extends BiFunction<URI, String, Optional<URI>> & Serializable> Repository ofUris(
            Map<String, URI> uris,
            F versionResolver,
            Consumer<URI> callback) {
        return (_, coordinate) -> {
            URI candidate = uris.get(coordinate);
            if (candidate == null && versionResolver != null) {
                int slash = coordinate.lastIndexOf('/');
                if (slash > 0) {
                    URI base = uris.get(coordinate.substring(0, slash));
                    if (base != null) {
                        // For a versioned request, the rewriter must produce a version-specific URL.
                        // If it can't (e.g. the registered URL isn't in Maven layout), treat it as a
                        // miss rather than silently serving whichever version the bare-name URL points at.
                        candidate = versionResolver.apply(base, coordinate.substring(slash + 1)).orElse(null);
                    }
                }
            }
            if (candidate == null) {
                return Optional.empty();
            }
            URI uri = candidate;
            callback.accept(uri);
            if (Objects.equals("file", uri.getScheme())) {
                return Optional.of(RepositoryItem.ofFile(Path.of(uri), true));
            } else {
                return Optional.of(() -> open(uri, null));
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
                                                BiFunction<Path, String, URI> resolver,
                                                Path cache) throws IOException {
        return ofProperties(suffix, folders, resolver, null, cache);
    }

    static <F extends BiFunction<URI, String, Optional<URI>> & Serializable> Map<String, Repository> ofProperties(
            String suffix,
            Iterable<Path> folders,
            BiFunction<Path, String, URI> resolver,
            F versionResolver,
            Path cache) throws IOException {
        Map<String, Map<String, URI>> artifacts = new HashMap<>();
        for (Path folder : folders) {
            Path file = folder.resolve(suffix);
            if (Files.exists(file)) {
                SequencedProperties properties = SequencedProperties.ofFiles(file);
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
                .map(entry -> Map.entry(entry.getKey(), Repository.ofUris(entry.getValue(), versionResolver).cached(cache)))
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
