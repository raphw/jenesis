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

    private final URI root;
    private final Path local;
    private final Map<String, URI> validations;

    public MavenRepository() {
        String environment = System.getenv("MAVEN_REPOSITORY_URI");
        if (environment != null && !environment.endsWith("/")) {
            environment += "/";
        }
        root = URI.create(environment == null ? "https://repo1.maven.org/maven2/" : environment);
        Path local = Path.of(System.getProperty("user.home"), ".m2", "repository");
        this.local = Files.isDirectory(local) ? local : null;
        validations = Map.of("SHA1", root);
    }

    public MavenRepository(URI root, Path local, Map<String, URI> validations) {
        this.root = root;
        this.local = local;
        this.validations = validations;
    }

    @Override
    public InputStreamSource fetch(String coordinate) throws IOException {
        String[] elements = coordinate.split(":");
        return switch (elements.length) {
            case 4 -> download(elements[0], elements[1], elements[2], null, "jar");
            case 5 -> download(elements[0], elements[1], elements[2], null, elements[3]);
            case 6 -> download(elements[0], elements[1], elements[2], elements[3], elements[4]);
            default -> throw new IllegalArgumentException("Insufficient Maven coordinate: " + coordinate);
        };
    }

    public InputStreamSource download(String groupId,
                                      String artifactId,
                                      String version,
                                      String classifier,
                                      String extension) throws IOException {
        String path = groupId.replace('.', '/')
                + "/" + artifactId
                + "/" + version
                + "/" + artifactId + "-" + version + (classifier == null ? "" : "-" + classifier)
                + "." + extension;
        Path cached = local == null ? null : local.resolve(path);
        if (cached != null) {
            if (Files.exists(cached)) {
                return new PathInputStreamSource(cached);
            }
            Files.createDirectories(cached.getParent());
        }
        Function<InputStream, InputStream> decorator = Function.identity();
        URI uri = root.resolve(path);
        if (!validations.isEmpty()) {
            for (Map.Entry<String, URI> validation : validations.entrySet()) {
                MessageDigest digest;
                try {
                    digest = MessageDigest.getInstance(validation.getKey());
                } catch (NoSuchAlgorithmException e) {
                    throw new IllegalStateException(e);
                }
                byte[] expected;
                try (InputStream inputStream = validation.getValue()
                        .resolve(path + "." + validation.getKey().toLowerCase())
                        .toURL()
                        .openConnection()
                        .getInputStream()) {
                    expected = inputStream.readAllBytes();
                }
                if (Objects.equals(uri.getScheme(), "file")) {
                    try (FileChannel channel = FileChannel.open(Path.of(uri))) {
                        digest.update(channel.map(FileChannel.MapMode.READ_ONLY, channel.position(), channel.size()));
                    }
                    if (!Arrays.equals(Base64.getDecoder().decode(expected), digest.digest())) {
                        throw new IOException("Digest did not match expectation");
                    }
                } else {
                    decorator = decorator.andThen(inputStream -> new ValidationInputStream(digest,
                            inputStream,
                            Base64.getDecoder().decode(expected)));
                }
                if (local != null) {
                    try (OutputStream outputStream = Files.newOutputStream(local.resolve(path
                            + "."
                            + validation.getKey().toLowerCase()))) {
                        outputStream.write(expected);
                    }
                }
            }
        }
        InputStream inputStream = decorator.apply(uri.toURL().openConnection().getInputStream());
        if (cached != null) {
            try (inputStream; OutputStream outputStream = Files.newOutputStream(cached)) {
                inputStream.transferTo(outputStream);
            } catch (Throwable t) {
                try {
                    Files.deleteIfExists(cached);
                } catch (Exception e) {
                    t.addSuppressed(e);
                }
                throw t;
            }
            return new PathInputStreamSource(cached);
        }
        return () -> inputStream;
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
