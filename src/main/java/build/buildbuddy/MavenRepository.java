package build.buildbuddy;

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
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

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
        return fetch(repository, groupId, artifactId, version, type, classifier, checksum);
    }

    private InputStreamSource fetch(URI repository,
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
        Path cached = local != null ? local.resolve(path) : null;
        if (cached != null) {
            if (Files.exists(cached)) {
                boolean validated = true;
                if (checksum == null) {
                    for (Map.Entry<String, URI> validation : validations.entrySet()) {
                        MessageDigest digest;
                        try {
                            digest = MessageDigest.getInstance(validation.getKey());
                        } catch (NoSuchAlgorithmException e) {
                            throw new IllegalStateException(e);
                        }
                        try (FileChannel channel = FileChannel.open(cached)) {
                            digest.update(channel.map(FileChannel.MapMode.READ_ONLY, channel.position(), channel.size()));
                        }
                        InputStreamSource source = fetch(validation.getValue(),
                                groupId,
                                artifactId,
                                version,
                                type,
                                classifier,
                                validation.getKey().toLowerCase());
                        byte[] expected;
                        try (InputStream inputStream = source.toInputStream()) {
                            expected = inputStream.readAllBytes();
                        }
                        if (!Arrays.equals(Base64.getDecoder().decode(expected), digest.digest())) {
                            Files.delete(cached);
                            Path hash = source.getPath().orElse(null);
                            if (hash != null) {
                                Files.delete(hash);
                            }
                            validated = false;
                            break;
                        }
                    }
                }
                if (validated) {
                    return new PathInputStreamSource(cached);
                }
            } else {
                Files.createDirectories(cached.getParent());
            }
        }
        Function<InputStream, InputStream> decorator = Function.identity();
        URI uri = repository.resolve(path);
        if (checksum == null) {
            for (Map.Entry<String, URI> validation : validations.entrySet()) {
                MessageDigest digest;
                try {
                    digest = MessageDigest.getInstance(validation.getKey());
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
                InputStreamSource source = fetch(validation.getValue(),
                        groupId,
                        artifactId,
                        version,
                        type,
                        classifier,
                        validation.getKey().toLowerCase());
                byte[] expected;
                try (InputStream inputStream = source.toInputStream()) {
                    expected = inputStream.readAllBytes();
                }
                if (Objects.equals(uri.getScheme(), "file")) {
                    try (FileChannel channel = FileChannel.open(Path.of(uri))) {
                        digest.update(channel.map(FileChannel.MapMode.READ_ONLY, channel.position(), channel.size()));
                    }
                    if (!Arrays.equals(Base64.getDecoder().decode(expected), digest.digest())) {
                        Path hash = source.getPath().orElse(null);
                        if (hash != null) {
                            Files.delete(hash);
                        }
                        throw new IOException("Digest did not match expectation");
                    }
                } else {
                    decorator = decorator.andThen(inputStream -> new ValidationInputStream(digest,
                            inputStream,
                            Base64.getDecoder().decode(expected)));
                }
            }
        }
        if (cached == null) {
            Function<InputStream, InputStream> fixture = decorator;
            return () -> fixture.apply(uri.toURL().openConnection().getInputStream());
        } else {
            Path temp = Files.createTempFile(
                    artifactId + "-" + version + (classifier == null ? "" : "-" + classifier),
                    type + (checksum == null ? "" : "." + checksum));
            try (InputStream inputStream = decorator.apply(uri.toURL().openConnection().getInputStream());
                 OutputStream outputStream = Files.newOutputStream(temp)) {
                inputStream.transferTo(outputStream);
            } catch (Throwable t) {
                try {
                    Files.delete(temp);
                } catch (Exception e) {
                    t.addSuppressed(e);
                }
                try {
                    Files.delete(temp);
                } catch (Exception e) {
                    t.addSuppressed(e);
                }
                throw t;
            }
            return new PathInputStreamSource(Files.move(temp, cached));
        }
    }

    private static class ValidationInputStream extends DigestInputStream {

        private final byte[] expected;

        private ValidationInputStream(MessageDigest digest, InputStream inputStream, byte[] expected) {
            super(inputStream, digest);
            this.expected = expected;
        }

        @Override
        public void close() throws IOException {
            super.close();
            if (!Arrays.equals(expected, getMessageDigest().digest())) {
                throw new IOException("Digest did not match expectation");
            }
        }
    }
}
