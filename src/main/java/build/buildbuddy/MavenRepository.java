package build.buildbuddy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

public class MavenRepository implements Repository {

    private final URI root;
    private final Path local;

    public MavenRepository() {
        String environment = System.getenv("MAVEN_REPOSITORY_URI");
        if (environment != null && !environment.endsWith("/")) {
            environment += "/";
        }
        root = URI.create(environment == null ? "https://repo1.maven.org/maven2/" : environment);
        Path local = Path.of(System.getProperty("user.home"), ".m2", "repository");
        this.local = Files.isDirectory(local) ? local : null;
    }

    public MavenRepository(URI root, Path local) {
        this.root = root;
        this.local = local;
    }

    @Override
    public InputStream fetch(String coordinate) throws IOException {
        String[] elements = coordinate.split(":");
        return switch (elements.length) {
            case 4 -> download(elements[0], elements[1], elements[2], null, "jar");
            case 5 -> download(elements[0], elements[1], elements[2], null, elements[3]);
            case 6 -> download(elements[0], elements[1], elements[2], elements[3], elements[4]);
            default -> throw new IllegalArgumentException("Insufficient Maven coordinate: " + coordinate);
        };
    }

    public InputStream download(String groupId,
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
                return Files.newInputStream(cached);
            }
        }
        InputStream inputStream = root.resolve(path).toURL().openConnection().getInputStream();
        if (cached != null) {
            Files.createDirectories(cached.getParent());
            try (inputStream; OutputStream outputStream = Files.newOutputStream(cached)) {
                    inputStream.transferTo(outputStream);
            }
            return Files.newInputStream(cached);
        }
        return inputStream;
    }
}
