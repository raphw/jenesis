package build.jenesis;

import module java.base;

public final class BuildExecutorHttpCache implements BuildExecutorCache {

    public static final String KEY = "Jenesis-Cache-Key";
    public static final String PROJECT = "Jenesis-Cache-Project";

    private final URI uri;
    private final String key;
    private final String project;
    private final String algorithm;
    private final Duration connectTimeout;
    private final boolean read;
    private final boolean write;

    public BuildExecutorHttpCache(URI uri) {
        this(uri,
                System.getProperty("jenesis.cache.key", System.getenv("JENESIS_CACHE_KEY")),
                System.getProperty("jenesis.cache.project", System.getenv("JENESIS_CACHE_PROJECT")),
                "SHA-256",
                defaultConnectTimeout(),
                true,
                true);
    }

    private BuildExecutorHttpCache(URI uri,
                                   String key,
                                   String project,
                                   String algorithm,
                                   Duration connectTimeout,
                                   boolean read,
                                   boolean write) {
        this.uri = uri;
        this.key = key;
        this.project = project;
        this.algorithm = algorithm;
        this.connectTimeout = connectTimeout;
        this.read = read;
        this.write = write;
    }

    private static Duration defaultConnectTimeout() {
        String timeout = System.getProperty("jenesis.cache.timeout");
        return timeout == null ? Duration.ofSeconds(1) : Duration.parse(timeout);
    }

    public BuildExecutorHttpCache key(String key) {
        return new BuildExecutorHttpCache(uri, key, project, algorithm, connectTimeout, read, write);
    }

    public BuildExecutorHttpCache project(String project) {
        return new BuildExecutorHttpCache(uri, key, project, algorithm, connectTimeout, read, write);
    }

    public BuildExecutorHttpCache algorithm(String algorithm) {
        return new BuildExecutorHttpCache(uri, key, project, algorithm, connectTimeout, read, write);
    }

    public BuildExecutorHttpCache connectTimeout(Duration connectTimeout) {
        return new BuildExecutorHttpCache(uri, key, project, algorithm, connectTimeout, read, write);
    }

    public BuildExecutorHttpCache read(boolean read) {
        return new BuildExecutorHttpCache(uri, key, project, algorithm, connectTimeout, read, write);
    }

    public BuildExecutorHttpCache write(boolean write) {
        return new BuildExecutorHttpCache(uri, key, project, algorithm, connectTimeout, read, write);
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    @Override
    public Optional<BuildStepResult> fetch(Executor executor,
                                           String identity,
                                           byte[] step,
                                           SequencedMap<String, Map<Path, byte[]>> inputs,
                                           Path target) throws IOException {
        if (!read) {
            return Optional.empty();
        }
        HttpURLConnection connection = connect("GET", step, inputs);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return Optional.empty();
            }
            try (InputStream stream = connection.getInputStream()) {
                unzip(stream, target);
            }
            return Optional.of(new BuildStepResult(true));
        } catch (IOException _) {
            clean(target);
            return Optional.empty();
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public void store(Executor executor,
                      String identity,
                      byte[] step,
                      SequencedMap<String, Map<Path, byte[]>> inputs,
                      Path output) throws IOException {
        if (write) {
            upload(step, inputs, output);
        }
    }

    @Override
    public boolean stores() {
        return write;
    }

    @Override
    public void touch(Executor executor,
                      String identity,
                      byte[] step,
                      SequencedMap<String, Map<Path, byte[]>> inputs) {
        if (!read) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    HttpURLConnection connection = connect("HEAD", step, inputs);
                    try {
                        connection.getResponseCode();
                    } finally {
                        connection.disconnect();
                    }
                } catch (IOException | RuntimeException _) {
                }
            });
        } catch (RejectedExecutionException _) {
        }
    }

    private void upload(byte[] step, SequencedMap<String, Map<Path, byte[]>> inputs, Path output) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("jenesis-cache", ".zip");
            zip(output, temporary);
            HttpURLConnection connection = connect("PUT", step, inputs);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(Files.size(temporary));
            connection.setRequestProperty("Content-Type", "application/zip");
            connection.setRequestProperty("Expect", "100-continue");
            try (OutputStream out = connection.getOutputStream()) {
                Files.copy(temporary, out);
            } catch (ProtocolException _) {
                // The server answered the Expect: 100-continue with a final status instead of
                // 100 Continue (it already holds the entry, or refuses the write), so the body
                // is never sent; the status is read below to release the connection.
            }
            connection.getResponseCode();
            connection.disconnect();
        } catch (IOException _) {
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException _) {
                }
            }
        }
    }

    private HttpURLConnection connect(String method, byte[] step, SequencedMap<String, Map<Path, byte[]>> inputs) throws IOException {
        String base = uri.toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        URI target = URI.create(base
                + "/" + HexFormat.of().formatHex(step)
                + "/" + HexFormat.of().formatHex(fold(inputs)));
        String scheme = target.getScheme(), host = target.getHost();
        boolean loopback = "localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        if (!"https".equals(scheme) && !loopback && !Boolean.getBoolean("jenesis.cache.insecure")) {
            throw new IllegalStateException("Refusing to send the cache key over insecure scheme '"
                    + scheme
                    + "': "
                    + target
                    + " (set -Djenesis.cache.insecure=true to allow plaintext)");
        }
        HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout((int) Math.min(connectTimeout.toMillis(), Integer.MAX_VALUE));
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "Jenesis");
        if (key != null) {
            connection.setRequestProperty(KEY, key);
        }
        if (project != null) {
            connection.setRequestProperty(PROJECT, project);
        }
        return connection;
    }

    private byte[] fold(SequencedMap<String, Map<Path, byte[]>> inputs) {
        MessageDigest message;
        try {
            message = MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        inputs.forEach((argument, files) -> {
            message.update(argument.getBytes(StandardCharsets.UTF_8));
            message.update((byte) 0);
            files.forEach((path, hash) -> {
                message.update(path.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                message.update((byte) 0);
                message.update(hash);
            });
        });
        return message.digest();
    }

    private static void zip(Path source, Path target) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    zip.putNextEntry(new ZipEntry(source.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, zip);
                    zip.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    private static void unzip(InputStream source, Path target) throws IOException {
        Path base = target.normalize();
        ZipInputStream zip = new ZipInputStream(source);
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            if (entry.isDirectory()) {
                continue;
            }
            Path destination = base.resolve(entry.getName()).normalize();
            if (!destination.startsWith(base)) {
                throw new IOException("Bad cache entry: " + entry.getName());
            }
            Files.createDirectories(destination.getParent());
            Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void clean(Path target) {
        try (DirectoryStream<Path> children = Files.newDirectoryStream(target)) {
            for (Path child : children) {
                Files.walkFileTree(child, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                        Files.deleteIfExists(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException _) {
        }
    }
}
