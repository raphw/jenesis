package build.buildbuddy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import static java.util.Objects.requireNonNull;

public class Dependencies implements BuildStep {

    private final Map<String, Repository> repositories;

    public Dependencies() {
        repositories = Map.of("maven", new MavenRepository());
    }

    public Dependencies(Map<String, Repository> repositories) {
        this.repositories = repositories;
    }

    @Override
    public CompletionStage<Boolean> apply(Executor executor,
                                          Path previous,
                                          Path target,
                                          Map<String, BuildResult> dependencies) throws IOException {
        for (BuildResult result : dependencies.values()) {
            for (Path path : result.files().keySet()) {
                if (path.toString().endsWith(".dependencies")) {
                    Properties properties = new Properties();
                    try (InputStream inputStream = Files.newInputStream(result.folder().resolve(path))) {
                        properties.loadFromXML(inputStream);
                    }
                    for (String dependency : properties.stringPropertyNames()) {
                        String[] segments = dependency.split(":", 2);
                        Repository repository = requireNonNull(
                                repositories.get(segments.length == 1 ? "" : segments[0]),
                                "Could not resolve dependency: " + dependency);
                        try (
                                InputStream inputStream = repository.download(segments[segments.length == 1 ? 0 : 1]);
                                OutputStream outputStream = Files.newOutputStream(target.resolve(dependency))
                        ) {
                            inputStream.transferTo(outputStream);
                            // TODO: checksum validation of value, easy to do in parallel.
                        }
                    }
                }
            }
        }
        return CompletableFuture.completedStage(true);
    }
}
