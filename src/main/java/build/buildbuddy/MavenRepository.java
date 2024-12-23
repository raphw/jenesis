package build.buildbuddy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

public class MavenRepository {

    private final URI root;

    public MavenRepository() {
        String environment = System.getenv("MAVEN_REPOSITORY_URI");
        root = URI.create(environment == null ? "https://repo1.maven.org/maven2" : environment);
    }

    public MavenRepository(URI root) {
        this.root = root;
    }

    public InputStream download(String groupId,
                                String artifactId,
                                String version,
                                String classifier,
                                String extension) throws IOException {
        return root.resolve(root.getPath()
                + (root.getPath().endsWith("/") ? "" : "/") + groupId.replace('.', '/')
                + "/" + artifactId
                + "/" + version
                + "/" + artifactId + "-" + version + (classifier == null ? "" : "-" + classifier)
                + "." + extension).toURL().openConnection().getInputStream();
    }
}
