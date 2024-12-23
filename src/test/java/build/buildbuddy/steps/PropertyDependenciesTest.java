package build.buildbuddy.steps;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import build.buildbuddy.ChecksumStatus;
import build.buildbuddy.maven.MavenDefaultVersionNegotiator;
import build.buildbuddy.maven.MavenPomResolver;
import build.buildbuddy.maven.MavenRepository;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

public class PropertyDependenciesTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path previous, next, supplement, dependencies, repository;

    @Before
    public void setUp() throws Exception {
        Path root = temporaryFolder.newFolder("root").toPath();
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        dependencies = Files.createDirectory(root.resolve("dependencies"));
        repository = root.resolve("repository");
    }

    @Test
    public void can_resolve_dependencies() throws IOException, ExecutionException, InterruptedException, NoSuchAlgorithmException {
        Properties properties = new Properties();
        properties.setProperty("foo|bar", "1");
        properties.setProperty("qux|baz", "2");
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectory(dependencies.resolve(PropertyDependencies.DEPENDENCIES))
                .resolve("dependencies.properties"))) {
            properties.store(writer, null);
        }
        toFile("foo", "bar", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """, "foo");
        toFile("qux", "baz", "2", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>2</version>
                        </dependency>
                    </dependencies>
                </project>
                """, "bar");
        toFile("transitive", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """, "qux");
        MavenRepository mavenRepository = new MavenRepository(repository.toUri(), null, Map.of());
        BuildStepResult result = new PropertyDependencies(
                new MavenPomResolver(mavenRepository, MavenDefaultVersionNegotiator.maven(mavenRepository)),
                mavenRepository,
                "SHA256").apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(PropertyDependencies.DEPENDENCIES, "dependencies.properties"),
                                ChecksumStatus.ALTERED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(Dependencies.FLATTENED + "dependencies.properties"))) {
            dependencies.load(reader);
        }
        assertThat(dependencies.stringPropertyNames()).containsExactly(
                "maven|transitive|artifact|artifact|1|jar",
                "maven|foo|bar|bar|1|jar",
                "maven|qux|baz|baz|2|jar");
        assertThat(dependencies.getProperty("maven|transitive|artifact|artifact|1|jar")).isEqualTo(
                "SHA256|" + Base64.getEncoder().encodeToString(MessageDigest
                        .getInstance("SHA256")
                        .digest("qux".getBytes(StandardCharsets.UTF_8))));
        assertThat(dependencies.getProperty("maven|foo|bar|bar|1|jar")).isEqualTo(
                "SHA256|" + Base64.getEncoder().encodeToString(MessageDigest
                        .getInstance("SHA256")
                        .digest("foo".getBytes(StandardCharsets.UTF_8))));
        assertThat(dependencies.getProperty("maven|qux|baz|baz|2|jar")).isEqualTo(
                "SHA256|" + Base64.getEncoder().encodeToString(MessageDigest
                        .getInstance("SHA256")
                        .digest("bar".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void can_resolve_dependencies_without_checksum() throws IOException, ExecutionException, InterruptedException {
        Properties properties = new Properties();
        properties.setProperty("foo|bar", "1");
        properties.setProperty("qux|baz", "2");
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectory(dependencies.resolve(PropertyDependencies.DEPENDENCIES))
                .resolve("dependencies.properties"))) {
            properties.store(writer, null);
        }
        toFile("foo", "bar", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """, null);
        toFile("qux", "baz", "2", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>2</version>
                        </dependency>
                    </dependencies>
                </project>
                """, null);
        toFile("transitive", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """, null);
        MavenRepository mavenRepository = new MavenRepository(repository.toUri(), null, Map.of());
        BuildStepResult result = new PropertyDependencies(
                new MavenPomResolver(mavenRepository, MavenDefaultVersionNegotiator.maven(mavenRepository)),
                mavenRepository,
                null).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                Map.of("dependencies", new BuildStepArgument(
                        dependencies,
                        Map.of(
                                Path.of(PropertyDependencies.DEPENDENCIES, "dependencies.properties"),
                                ChecksumStatus.ALTERED)))).toCompletableFuture().get();
        assertThat(result.next()).isTrue();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(next.resolve(Dependencies.FLATTENED + "dependencies.properties"))) {
            dependencies.load(reader);
        }
        assertThat(dependencies.stringPropertyNames()).containsExactly(
                "maven|transitive|artifact|artifact|1|jar",
                "maven|foo|bar|bar|1|jar",
                "maven|qux|baz|baz|2|jar");
        assertThat(dependencies.getProperty("maven|transitive|artifact|artifact|1|jar")).isEmpty();
        assertThat(dependencies.getProperty("maven|foo|bar|bar|1|jar")).isEmpty();
        assertThat(dependencies.getProperty("maven|qux|baz|baz|2|jar")).isEmpty();
    }

    private void toFile(String groupId, String artifactId, String version, String pom, String jar) throws IOException {
        Files.writeString(Files
                .createDirectories(repository.resolve(groupId + "/" + artifactId + "/" + version))
                .resolve(artifactId + "-" + version + ".pom"), pom);
        if (jar != null) {
            Files.writeString(Files
                    .createDirectories(repository.resolve(groupId + "/" + artifactId + "/" + version))
                    .resolve(artifactId + "-" + version + ".jar"), jar);
        }
    }

}