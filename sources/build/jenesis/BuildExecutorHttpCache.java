package build.jenesis;

import module java.base;

public record BuildExecutorHttpCache(URI uri,
                                     String key,
                                     String algorithm,
                                     Duration connectTimeout,
                                     boolean read,
                                     boolean write) implements BuildExecutorCache {

    public static final String HEADER = "Jenesis-Cache-Key";

    public BuildExecutorHttpCache(URI uri, String key) {
        this(uri, key, "SHA-256", Duration.ofSeconds(1), true, true);
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
        if (!write) {
            return;
        }
        try {
            executor.execute(() -> upload(step, inputs, output));
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
            try (OutputStream out = connection.getOutputStream()) {
                Files.copy(temporary, out);
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
        if (!"https".equals(scheme) && !loopback && !Boolean.getBoolean("jenesis.executor.cache.insecure")) {
            throw new IllegalStateException("Refusing to send the cache key over insecure scheme '"
                    + scheme
                    + "': "
                    + target
                    + " (set -Djenesis.executor.cache.insecure=true to allow plaintext)");
        }
        HttpURLConnection connection = (HttpURLConnection) target.toURL().openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout((int) Math.min(connectTimeout.toMillis(), Integer.MAX_VALUE));
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "Jenesis");
        if (key != null) {
            connection.setRequestProperty(HEADER, key);
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
                message.update(path.toString().replace(File.separatorChar, '/').getBytes(StandardCharsets.UTF_8));
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
                    zip.putNextEntry(new ZipEntry(source.relativize(file).toString().replace(File.separatorChar, '/')));
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
}
