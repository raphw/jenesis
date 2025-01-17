package build.buildbuddy.maven;

import build.buildbuddy.RepositoryItem;

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

public class MavenDefaultRepository implements MavenRepository {

    private final URI repository;
    private final Path local;
    private final Map<String, URI> validations;

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
        return fetch(repository, groupId.replace('.', '/')
                + "/" + artifactId
                + "/" + version
                + "/" + artifactId + "-" + version + (classifier == null ? "" : "-" + classifier)
                + "." + type + (checksum == null ? "" : ("." + checksum)), checksum == null).materialize();
    }

    @Override
    public Optional<RepositoryItem> fetchMetadata(Executor executor,
                                                  String groupId,
                                                  String artifactId,
                                                  String checksum) throws IOException {
        return fetch(repository, groupId.replace('.', '/')
                + "/" + artifactId
                + "/maven-metadata.xml" + (checksum == null ? "" : "." + checksum), checksum == null).materialize();
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
        if (cached == null) {
            return () -> ValidatingInputStream.of(uri, digests);
        } else {
            int dash = path.lastIndexOf('/'), dot = path.indexOf('.', dash);
            return new LatentRepositoryItem(cached,
                    uri,
                    digests,
                    path.substring(dash + 1, dot),
                    path.substring(dot));
        }
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
            return ValidatingInputStream.of(uri, digests);
        }

        @Override
        public Optional<RepositoryItem> materialize() throws IOException {
            Optional<InputStream> candidate = ValidatingInputStream.of(uri, digests);
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            Path temporary = Files.createTempFile(prefix, suffix);
            try (InputStream inputStream = candidate.get()) {
                Files.copy(inputStream, temporary, StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable t) {
                Files.delete(temporary);
                throw t;
            }
            return Optional.of(new StoredRepositoryItem(Files.move(temporary, path)));
        }
    }

    private static class ValidatingInputStream extends FilterInputStream {

        private final Map<LazyRepositoryItem, MessageDigest> digests;

        private ValidatingInputStream(InputStream inputStream, Map<LazyRepositoryItem, MessageDigest> digests) {
            super(inputStream);
            this.digests = digests;
        }

        private static Optional<InputStream> of(URI uri, Map<LazyRepositoryItem, MessageDigest> digests) throws IOException {
            InputStream inputStream;
            try {
                inputStream = uri.toURL().openStream();
            } catch (FileNotFoundException ignored) {
                return Optional.empty();
            }
            if (digests.isEmpty()) {
                return Optional.of(inputStream);
            }
            for (MessageDigest digest : digests.values()) {
                inputStream = new DigestInputStream(inputStream, digest);
            }
            return Optional.of(new ValidatingInputStream(inputStream, digests));
        }

        @Override
        public void close() throws IOException {
            super.close();
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
            if (invalid == null) {
                for (Map.Entry<LazyRepositoryItem, byte[]> entry : results.entrySet()) {
                    entry.getKey().storeIfNotPresent(entry.getValue());
                }
            } else {
                for (LazyRepositoryItem item : digests.keySet()) {
                    item.deleteIfPresent();
                }
                throw new IllegalStateException("Failed checksum validation for " + invalid);
            }
        }
    }
}
