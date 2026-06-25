package build.jenesis.maven;

import module java.base;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;

public class MavenDefaultRepository implements MavenRepository {

    private final URI repository;
    private final Path local;
    private final boolean writable;
    private final Map<String, URI> validations;
    private final Consumer<String> callback;
    private final String token;

    public MavenDefaultRepository() {
        String environment = System.getProperty("jenesis.maven.uri", System.getenv("MAVEN_REPOSITORY_URI"));
        if (environment != null && !environment.endsWith("/")) {
            environment += "/";
        }
        repository = URI.create(environment == null ? "https://repo1.maven.org/maven2/" : environment);
        String localOverride = System.getProperty("jenesis.maven.local", System.getenv("MAVEN_REPOSITORY_LOCAL"));
        if (localOverride == null) {
            Path local = Path.of(System.getProperty("user.home"), ".m2", "repository");
            this.local = Files.isDirectory(local) ? local : null;
        } else {
            Path local = Path.of(localOverride);
            if (!Files.isDirectory(local)) {
                throw new IllegalStateException("Local Maven repository does not point at a directory: " + local);
            }
            this.local = local;
        }
        this.writable = this.local != null && Files.isWritable(this.local);
        token = System.getProperty("jenesis.maven.token", System.getenv("MAVEN_REPOSITORY_TOKEN"));
        Map<String, URI> validations = new LinkedHashMap<>();
        validations.put("SHA512", repository);
        validations.put("SHA256", repository);
        validations.put("SHA1", repository);
        this.validations = Collections.unmodifiableMap(validations);
        boolean verbose = Boolean.getBoolean("jenesis.print.fetch");
        callback = verbose ? path -> System.out.printf("%s%-11s%s %s%n",
                BuildExecutorCallback.YELLOW,
                "[FETCHED]",
                BuildExecutorCallback.RESET,
                repository.resolve(path)) : _ -> {
        };
    }

    public MavenDefaultRepository(URI repository, Path local, Map<String, URI> validations, Consumer<String> callback) {
        this(repository, local, validations, callback, null);
    }

    public MavenDefaultRepository(URI repository,
                                  Path local,
                                  Map<String, URI> validations,
                                  Consumer<String> callback,
                                  String token) {
        this.repository = repository;
        this.local = local;
        this.writable = local != null && Files.isWritable(local);
        this.validations = validations;
        this.callback = callback;
        this.token = token;
    }

    @SuppressWarnings("unchecked")
    public static <F extends BiFunction<URI, String, Optional<URI>> & Serializable> F versionResolver() {
        return (F) (BiFunction<URI, String, Optional<URI>> & Serializable) (uri, version) -> {
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
            } catch (URISyntaxException _) {
                return Optional.empty();
            }
        };
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
        callback.accept(path);
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
        callback.accept(path);
        return fetch(repository, path, checksum == null).materialize();
    }

    private LazyRepositoryItem fetch(URI repository, String path, boolean validate) throws IOException {
        Path cached = local == null ? null : local.resolve(path);
        if (cached != null && !cached.normalize().startsWith(local.normalize())) {
            throw new IllegalStateException("Resolved path escapes the local repository root: " + path);
        }
        if (cached != null) {
            if (Files.exists(cached)) {
                boolean valid = true;
                if (validate) {
                    Map<LazyRepositoryItem, byte[]> results = new HashMap<>();
                    for (Map.Entry<String, URI> entry : validations.entrySet()) {
                        LazyRepositoryItem item = fetch(
                                entry.getValue(),
                                path + "." + entry.getKey().toLowerCase(Locale.ROOT),
                                false);
                        if (valid) {
                            MessageDigest digest;
                            try {
                                digest = MessageDigest.getInstance(entry.getKey());
                            } catch (NoSuchAlgorithmException e) {
                                throw new IllegalStateException(e);
                            }
                            try (FileChannel channel = FileChannel.open(cached)) {
                                ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
                                while (channel.read(buffer) != -1) {
                                    buffer.flip();
                                    digest.update(buffer);
                                    buffer.clear();
                                }
                            }
                            Optional<InputStream> candidate = item.toLazyInputStream();
                            if (candidate.isPresent()) {
                                byte[] expected;
                                try (InputStream inputStream = candidate.get()) {
                                    expected = inputStream.readAllBytes();
                                }
                                results.put(item, expected);
                                String text = new String(expected, StandardCharsets.UTF_8).strip();
                                int end = 0;
                                while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                                    end++;
                                }
                                valid = Arrays.equals(
                                        HexFormat.of().parseHex(text.substring(0, end)),
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
                    } else if (writable) {
                        try {
                            Files.delete(cached);
                            for (LazyRepositoryItem item : results.keySet()) {
                                item.deleteIfPresent();
                            }
                        } catch (IOException _) {
                        }
                    }
                }
                if (valid) {
                    return new StoredRepositoryItem(cached);
                }
            } else if (writable) {
                try {
                    Files.createDirectories(cached.getParent());
                } catch (IOException _) {
                    cached = null;
                }
            }
        }
        Map<LazyRepositoryItem, MessageDigest> digests = new HashMap<>();
        if (validate) {
            for (Map.Entry<String, URI> entry : validations.entrySet()) {
                LazyRepositoryItem item = fetch(entry.getValue(),
                        path + "." + entry.getKey().toLowerCase(Locale.ROOT),
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
        return new LatentRepositoryItem(writable ? cached : null,
                uri,
                digests,
                path.substring(dash + 1, dot),
                path.substring(dot),
                token);
    }

    private static InputStream openStream(URI uri, String token) throws IOException {
        return Repository.open(uri, token);
    }

    private static Optional<Path> download(URI uri,
                                           Map<LazyRepositoryItem, MessageDigest> digests,
                                           String prefix,
                                           String suffix,
                                           String token) throws IOException {
        Path temporary = Files.createTempFile(prefix, suffix);
        try {
            IOException failure = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                try (InputStream stream = openStream(uri, token)) {
                    Files.copy(stream, temporary, StandardCopyOption.REPLACE_EXISTING);
                    failure = null;
                    break;
                } catch (FileNotFoundException _) {
                    Files.deleteIfExists(temporary);
                    return Optional.empty();
                } catch (IOException e) {
                    failure = e;
                    if (attempt < 3) {
                        Thread.sleep(500L << attempt);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Files.deleteIfExists(temporary);
            throw new IOException("Interrupted while fetching " + uri, e);
        } catch (Throwable t) {
            Files.deleteIfExists(temporary);
            throw t;
        }
        if (digests.isEmpty()) {
            return Optional.of(temporary);
        }
        try {
            try (FileChannel channel = FileChannel.open(temporary)) {
                ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
                while (channel.read(buffer) != -1) {
                    buffer.flip();
                    for (MessageDigest digest : digests.values()) {
                        digest.update(buffer);
                        buffer.rewind();
                    }
                    buffer.clear();
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
                    String text = new String(expected, StandardCharsets.UTF_8).strip();
                    int end = 0;
                    while (end < text.length() && !Character.isWhitespace(text.charAt(end))) {
                        end++;
                    }
                    if (!Arrays.equals(
                            HexFormat.of().parseHex(text.substring(0, end)),
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
        public Optional<Path> file() {
            return Optional.of(path);
        }

        @Override
        public boolean local() {
            return true;
        }
    }

    record LatentRepositoryItem(Path path,
                                URI uri,
                                Map<LazyRepositoryItem, MessageDigest> digests,
                                String prefix,
                                String suffix,
                                String token) implements LazyRepositoryItem {

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
            try {
                Files.move(temporary, path);
            } catch (IOException _) {
                Files.deleteIfExists(temporary);
            }
        }

        @Override
        public Optional<InputStream> toLazyInputStream() throws IOException {
            Optional<Path> temporary = download(uri, digests, prefix, suffix, token);
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
            Optional<Path> temporary = download(uri, digests, prefix, suffix, token);
            if (temporary.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(new StoredRepositoryItem(Files.move(temporary.get(), path)));
            } catch (IOException _) {
                return Optional.of(new StoredRepositoryItem(temporary.get()));
            }
        }
    }
}
