package build.buildbuddy.maven;

import build.buildbuddy.Repository;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class MavenRepository implements Repository {

    private final URI repository;
    private final Path local;
    private final Map<String, URI> validations;

    public MavenRepository() {
        String environment = System.getenv("MAVEN_REPOSITORY_URI");
        if (environment != null && !environment.endsWith("/")) {
            environment += "/";
        }
        repository = URI.create(environment == null ? "https://repo1.maven.org/maven2/" : environment);
        Path local = Path.of(System.getProperty("user.home"), ".m2", "repository");
        this.local = Files.isDirectory(local) ? local : null;
        validations = Map.of("SHA1", repository);
    }

    public MavenRepository(URI repository, Path local, Map<String, URI> validations) {
        this.repository = repository;
        this.local = local;
        this.validations = validations;
    }

    @Override
    public InputStreamSource fetch(String coordinate) throws IOException {
        String[] elements = coordinate.split(":");
        return switch (elements.length) {
            case 4 -> fetch(elements[0], elements[1], elements[2], "jar", null, null);
            case 5 -> fetch(elements[0], elements[1], elements[2], elements[3], null, null);
            case 6 -> fetch(elements[0], elements[1], elements[2], elements[4], elements[3], null);
            default -> throw new IllegalArgumentException("Insufficient Maven coordinate: " + coordinate);
        };
    }

    public InputStreamSource fetch(String groupId,
                                   String artifactId,
                                   String version,
                                   String type,
                                   String classifier,
                                   String checksum) throws IOException {
        return fetch(repository, groupId.replace('.', '/')
                + "/" + artifactId
                + "/" + version
                + "/" + artifactId + "-" + version + (classifier == null ? "" : "-" + classifier)
                + "." + type + (checksum == null ? "" : ("." + checksum)), checksum == null).materialize();
    }

    public InputStreamSource fetchMetadata(String groupId,
                                           String artifactId,
                                           String checksum) throws IOException {
        return fetch(repository, groupId.replace('.', '/')
                + "/" + artifactId
                + "/maven-metadata.xml" + (checksum == null ? "" : "." + checksum), checksum == null).materialize();
    }

    private LazyInputStreamSource fetch(URI repository, String path, boolean validate) throws IOException {
        Path cached = local == null ? null : local.resolve(path);
        if (cached != null) {
            if (Files.exists(cached)) {
                boolean valid = true;
                if (validate) {
                    Map<LazyInputStreamSource, byte[]> results = new HashMap<>();
                    for (Map.Entry<String, URI> entry : validations.entrySet()) {
                        LazyInputStreamSource source = fetch(
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
                            byte[] expected;
                            try (InputStream inputStream = source.toInputStream()) {
                                expected = inputStream.readAllBytes();
                            }
                            results.put(source, expected);
                            valid = Arrays.equals(Base64.getDecoder().decode(expected), digest.digest());
                        } else {
                            results.put(source, null);
                        }
                    }
                    if (valid) {
                        for (Map.Entry<LazyInputStreamSource, byte[]> entry : results.entrySet()) {
                            entry.getKey().storeIfNotPresent(entry.getValue());
                        }
                    } else {
                        Files.delete(cached);
                        for (LazyInputStreamSource source : results.keySet()) {
                            source.deleteIfPresent();
                        }
                    }
                }
                if (valid) {
                    return new StoredInputStreamSource(cached);
                }
            } else {
                Files.createDirectories(cached.getParent());
            }
        }
        Map<LazyInputStreamSource, MessageDigest> digests = new HashMap<>();
        if (validate) {
            for (Map.Entry<String, URI> entry : validations.entrySet()) {
                LazyInputStreamSource source = fetch(entry.getValue(),
                        path + "." + entry.getKey().toLowerCase(),
                        false);
                MessageDigest digest;
                try {
                    digest = MessageDigest.getInstance(entry.getKey());
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
                digests.put(source, digest);
            }
        }
        URI uri = repository.resolve(path);
        if (cached == null) {
            return () -> ValidatingInputStream.of(uri.toURL().openStream(), digests);
        } else {
            return new LatentInputStreamSource(cached,
                    uri,
                    digests,
                    path.substring(path.lastIndexOf('/') + 1, path.indexOf('.')),
                    path.substring(path.indexOf('.') + 1));
        }
    }

    @FunctionalInterface
    private interface LazyInputStreamSource extends InputStreamSource {

        default void deleteIfPresent() throws IOException {
        }

        default void storeIfNotPresent(byte[] bytes) throws IOException {
        }

        default InputStreamSource materialize() throws IOException {
            return this;
        }
    }

    record StoredInputStreamSource(Path path) implements LazyInputStreamSource {

        @Override
        public void deleteIfPresent() throws IOException {
            Files.delete(path);
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

    record LatentInputStreamSource(Path path,
                                   URI uri,
                                   Map<LazyInputStreamSource, MessageDigest> digests,
                                   String prefix,
                                   String suffix) implements LazyInputStreamSource {

        @Override
        public InputStream toInputStream() throws IOException {
            return ValidatingInputStream.of(uri.toURL().openStream(), digests);
        }

        @Override
        public void storeIfNotPresent(byte[] bytes) throws IOException {
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
        public InputStreamSource materialize() throws IOException {
            Path temporary = Files.createTempFile(prefix, suffix);
            try (InputStream inputStream = toInputStream();
                 OutputStream outputStream = Files.newOutputStream(temporary)) {
                inputStream.transferTo(outputStream);
            } catch (Throwable t) {
                Files.delete(temporary);
                throw t;
            }
            return new StoredInputStreamSource(Files.move(temporary, path));
        }
    }

    private static class ValidatingInputStream extends FilterInputStream {

        private final Map<LazyInputStreamSource, MessageDigest> digests;

        private ValidatingInputStream(InputStream inputStream, Map<LazyInputStreamSource, MessageDigest> digests) {
            super(inputStream);
            this.digests = digests;
        }

        private static InputStream of(InputStream inputStream, Map<LazyInputStreamSource, MessageDigest> digests) {
            if (digests.isEmpty()) {
                return inputStream;
            }
            for (MessageDigest digest : digests.values()) {
                inputStream = new DigestInputStream(inputStream, digest);
            }
            return new ValidatingInputStream(inputStream, digests);
        }

        @Override
        public void close() throws IOException {
            super.close();
            boolean valid = true;
            Map<LazyInputStreamSource, byte[]> results = new HashMap<>();
            for (Map.Entry<LazyInputStreamSource, MessageDigest> entry : digests.entrySet()) {
                byte[] expected;
                try (InputStream inputStream = entry.getKey().toInputStream()) {
                    expected = inputStream.readAllBytes();
                }
                results.put(entry.getKey(), expected);
                if (!(valid = Arrays.equals(Base64.getDecoder().decode(expected), entry.getValue().digest()))) {
                    break;
                }
            }
            if (valid) {
                for (Map.Entry<LazyInputStreamSource, byte[]> entry : results.entrySet()) {
                    entry.getKey().storeIfNotPresent(entry.getValue());
                }
            } else {
                for (LazyInputStreamSource source : digests.keySet()) {
                    source.deleteIfPresent();
                }
                throw new IOException("Failed checksum validation");
            }
        }
    }
}
