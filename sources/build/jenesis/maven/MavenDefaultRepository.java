package build.jenesis.maven;

import module java.base;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.RepositoryItem;

public class MavenDefaultRepository implements MavenRepository {

    private final URI repository;
    private final Path local;
    private final Map<String, URI> validations;
    private final boolean verbose = Boolean.getBoolean("jenesis.verbose");

    public MavenDefaultRepository() {
        String environment = System.getenv("MAVEN_REPOSITORY_URI");
        if (environment != null && !environment.endsWith("/")) {
            environment += "/";
        }
        repository = URI.create(environment == null ? "https://repo1.maven.org/maven2/" : environment);
        Path local = Path.of(System.getProperty("user.home"), ".m2", "repository");
        this.local = Files.isDirectory(local) ? local : null;
        validations = Map.of("SHA1", repository);
    }

    public MavenDefaultRepository(URI repository, Path local, Map<String, URI> validations) {
        this.repository = repository;
        this.local = local;
        this.validations = validations;
    }

    @Override
    public Optional<RepositoryItem> fetch(Executor executor,
                                          String groupId,
                                          String artifactId,
                                          String version,
                                          String type,
                                          String classifier,
                                          String checksum) throws IOException {
        String path = groupId.replace('.', '/')
                + "/" + artifactId
                + "/" + version
                + "/" + artifactId + "-" + version + (classifier == null ? "" : "-" + classifier)
                + "." + type + (checksum == null ? "" : ("." + checksum));
        if (verbose) {
            System.out.printf("%s%-11s%s %s%n",
                    BuildExecutorCallback.YELLOW, "[FETCHED]", BuildExecutorCallback.RESET, repository.resolve(path));
        }
        return fetch(repository, path, checksum == null).materialize();
    }

    @Override
    public Optional<RepositoryItem> fetchMetadata(Executor executor,
                                                  String groupId,
                                                  String artifactId,
                                                  String checksum) throws IOException {
        String path = groupId.replace('.', '/')
                + "/" + artifactId
                + "/maven-metadata.xml" + (checksum == null ? "" : "." + checksum);
        if (verbose) {
            System.out.printf("%s%-11s%s %s%n",
                    BuildExecutorCallback.YELLOW, "[FETCHED]", BuildExecutorCallback.RESET, repository.resolve(path));
        }
        return fetch(repository, path, checksum == null).materialize();
    }

    private LazyRepositoryItem fetch(URI repository, String path, boolean validate) throws IOException {
        Path cached = local == null ? null : local.resolve(path);
        if (cached != null) {
            if (Files.exists(cached)) {
                boolean valid = true;
                if (validate) {
                    Map<LazyRepositoryItem, byte[]> results = new HashMap<>();
                    for (Map.Entry<String, URI> entry : validations.entrySet()) {
                        LazyRepositoryItem item = fetch(
                                entry.getValue(),
                                path + "." + entry.getKey().toLowerCase(),
                                false);
                        if (valid) {
                            MessageDigest digest;
                            try {
                                digest = MessageDigest.getInstance(entry.getKey());
                            } catch (NoSuchAlgorithmException e) {
                                throw new IllegalStateException(e);
                            }
                            try (FileChannel channel = FileChannel.open(cached)) {
                                digest.update(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()));
                            }
                            Optional<InputStream> candidate = item.toLazyInputStream();
                            if (candidate.isPresent()) {
                                byte[] expected;
                                try (InputStream inputStream = candidate.get()) {
                                    expected = inputStream.readAllBytes();
                                }
                                results.put(item, expected);
                                valid = Arrays.equals(
                                        HexFormat.of().parseHex(new String(expected, StandardCharsets.UTF_8)),
                                        digest.digest());
                            }
                        } else {
                            results.put(item, null);
                        }
                    }
                    if (valid) {
                        for (Map.Entry<LazyRepositoryItem, byte[]> entry : results.entrySet()) {
                            entry.getKey().storeIfNotPresent(entry.getValue());
                        }
                    } else {
                        Files.delete(cached);
                        for (LazyRepositoryItem item : results.keySet()) {
                            item.deleteIfPresent();
                        }
                    }
                }
                if (valid) {
                    return new StoredRepositoryItem(cached);
                }
            } else {
                Files.createDirectories(cached.getParent());
            }
        }
        Map<LazyRepositoryItem, MessageDigest> digests = new HashMap<>();
        if (validate) {
            for (Map.Entry<String, URI> entry : validations.entrySet()) {
                LazyRepositoryItem item = fetch(entry.getValue(),
                        path + "." + entry.getKey().toLowerCase(),
                        false);
                MessageDigest digest;
                try {
                    digest = MessageDigest.getInstance(entry.getKey());
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
                digests.put(item, digest);
            }
        }
        URI uri = repository.resolve(path);
        int dash = path.lastIndexOf('/'), dot = path.indexOf('.', dash);
        return new LatentRepositoryItem(cached,
                uri,
                digests,
                path.substring(dash + 1, dot),
                path.substring(dot));
    }

    private static Optional<Path> download(URI uri,
                                           Map<LazyRepositoryItem, MessageDigest> digests,
                                           String prefix,
                                           String suffix) throws IOException {
        InputStream stream;
        try {
            stream = uri.toURL().openStream();
        } catch (FileNotFoundException _) {
            return Optional.empty();
        }
        Path temporary = Files.createTempFile(prefix, suffix);
        try (stream) {
            Files.copy(stream, temporary, StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            Files.deleteIfExists(temporary);
            throw t;
        }
        if (digests.isEmpty()) {
            return Optional.of(temporary);
        }
        try {
            for (MessageDigest digest : digests.values()) {
                try (FileChannel channel = FileChannel.open(temporary)) {
                    digest.update(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()));
                }
            }
            String invalid = null;
            Map<LazyRepositoryItem, byte[]> results = new HashMap<>();
            for (Map.Entry<LazyRepositoryItem, MessageDigest> entry : digests.entrySet()) {
                Optional<InputStream> candidate = entry.getKey().toLazyInputStream();
                if (candidate.isPresent()) {
                    byte[] expected;
                    try (InputStream inputStream = candidate.get()) {
                        expected = inputStream.readAllBytes();
                    }
                    results.put(entry.getKey(), expected);
                    if (!Arrays.equals(
                            HexFormat.of().parseHex(new String(expected, StandardCharsets.UTF_8)),
                            entry.getValue().digest())) {
                        invalid = entry.getValue().getAlgorithm();
                        break;
                    }
                }
            }
            if (invalid != null) {
                for (LazyRepositoryItem item : digests.keySet()) {
                    item.deleteIfPresent();
                }
                throw new IllegalStateException("Failed checksum validation for " + invalid);
            }
            for (Map.Entry<LazyRepositoryItem, byte[]> entry : results.entrySet()) {
                entry.getKey().storeIfNotPresent(entry.getValue());
            }
        } catch (Throwable t) {
            Files.deleteIfExists(temporary);
            throw t;
        }
        return Optional.of(temporary);
    }

    private interface LazyRepositoryItem {

        default void deleteIfPresent() throws IOException {
        }

        default void storeIfNotPresent(byte[] bytes) throws IOException {
        }

        default Optional<RepositoryItem> materialize() throws IOException {
            return toLazyInputStream().map(inputStream -> () -> inputStream);
        }

        Optional<InputStream> toLazyInputStream() throws IOException;
    }

    record StoredRepositoryItem(Path path) implements LazyRepositoryItem, RepositoryItem {

        @Override
        public void deleteIfPresent() throws IOException {
            Files.delete(path);
        }

        @Override
        public Optional<RepositoryItem> materialize() {
            return Optional.of(this);
        }

        @Override
        public Optional<InputStream> toLazyInputStream() throws IOException {
            return Optional.of(toInputStream());
        }

        @Override
        public InputStream toInputStream() throws IOException {
            return Files.newInputStream(path);
        }

        @Override
        public Optional<Path> getFile() {
            return Optional.of(path);
        }
    }

    record LatentRepositoryItem(Path path,
                                URI uri,
                                Map<LazyRepositoryItem, MessageDigest> digests,
                                String prefix,
                                String suffix) implements LazyRepositoryItem {

        @Override
        public void storeIfNotPresent(byte[] bytes) throws IOException {
            if (path == null) {
                return;
            }
            Path temporary = Files.createTempFile(prefix, suffix);
            try (OutputStream outputStream = Files.newOutputStream(temporary)) {
                outputStream.write(bytes);
            } catch (Throwable t) {
                Files.delete(temporary);
                throw t;
            }
            Files.move(temporary, path);
        }

        @Override
        public Optional<InputStream> toLazyInputStream() throws IOException {
            Optional<Path> temporary = download(uri, digests, prefix, suffix);
            if (temporary.isEmpty()) {
                return Optional.empty();
            }
            Path file = temporary.get();
            InputStream stream;
            try {
                stream = Files.newInputStream(file);
            } catch (Throwable t) {
                Files.deleteIfExists(file);
                throw t;
            }
            return Optional.of(new FilterInputStream(stream) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        Files.deleteIfExists(file);
                    }
                }
            });
        }

        @Override
        public Optional<RepositoryItem> materialize() throws IOException {
            if (path == null) {
                return LazyRepositoryItem.super.materialize();
            }
            Optional<Path> temporary = download(uri, digests, prefix, suffix);
            if (temporary.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new StoredRepositoryItem(Files.move(temporary.get(), path)));
        }
    }
}
