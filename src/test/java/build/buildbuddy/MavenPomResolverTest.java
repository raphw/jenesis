package build.buildbuddy;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class MavenPomResolverTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path repository;

    @Before
    public void setUp() throws Exception {
        repository = temporaryFolder.newFolder("repository").toPath();
    }

    @Test
    public void can_resolve_dependencies() throws IOException {
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("group/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1.0.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """);
        }
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("other/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                    </project>
                    """);
        }
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(), null)).resolve("group",
                "artifact",
                "1.0.0");
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1.0.0",
                "jar",
                null,
                false));
    }

    @Test
    public void can_resolve_dependencies_from_parent() throws IOException {
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("group/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                        <parent>
                            <groupId>parent</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1.0.0</version>
                        </parent>
                    </project>
                    """);
        }
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("parent/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1.0.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """);
        }
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("other/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                    </project>
                    """);
        }
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(), null)).resolve("group",
                "artifact",
                "1.0.0");
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1.0.0",
                "jar",
                null,
                false));
    }

    @Test
    public void can_resolve_duplicate_dependencies_from_parent() throws IOException {
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("group/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                        <parent>
                            <groupId>parent</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1.0.0</version>
                        </parent>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>artifact</artifactId>
                                <version>2.0.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """);
        }
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("parent/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1.0.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """);
        }
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("other/artifact/2.0.0"))
                .resolve("artifact-2.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                    </project>
                    """);
        }
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("other/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                    </project>
                    """);
        }
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(), null)).resolve("group",
                "artifact",
                "1.0.0");
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "2.0.0",
                "jar",
                null,
                false));
    }

    @Test
    public void can_resolve_transitive_dependencies() throws IOException {
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("group/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1.0.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """);
        }
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("other/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                        <dependencies>
                            <dependency>
                                <groupId>transitive</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1.0.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """);
        }
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("transitive/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                    </project>
                    """);
        }
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(), null)).resolve("group",
                "artifact",
                "1.0.0");
        assertThat(dependencies).containsExactly(
                new MavenDependency("other",
                        "artifact",
                        "1.0.0",
                        "jar",
                        null,
                        false),
                new MavenDependency("transitive",
                        "artifact",
                        "1.0.0",
                        "jar",
                        null,
                        false));
    }

    @Test
    public void can_resolve_transitive_dependencies_with_exclusion() throws IOException {
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("group/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1.0.0</version>
                                <exclusions>
                                    <exclusion>
                                        <groupId>transitive</groupId>
                                        <artifactId>artifact</artifactId>
                                    </exclusion>
                                </exclusions>
                            </dependency>
                        </dependencies>
                    </project>
                    """);
        }
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("other/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                        <dependencies>
                            <dependency>
                                <groupId>transitive</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1.0.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                    """);
        }
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("transitive/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                    </project>
                    """);
        }
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(), null)).resolve("group",
                "artifact",
                "1.0.0");
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1.0.0",
                "jar",
                null,
                false));
    }

    @Test
    public void can_resolve_dependency_configuration() throws IOException {
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("group/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>artifact</artifactId>
                            </dependency>
                        </dependencies>
                        <dependencyManagement>
                            <dependencies>
                                <dependency>
                                    <groupId>other</groupId>
                                    <artifactId>artifact</artifactId>
                                    <version>1.0.0</version>
                                </dependency>
                            </dependencies>
                        </dependencyManagement>
                    </project>
                    """);
        }
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve("other/artifact/1.0.0"))
                .resolve("artifact-1.0.0.pom"))) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                        <modelVersion>4.0.0</modelVersion>
                    </project>
                    """);
        }
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(), null)).resolve("group",
                "artifact",
                "1.0.0");
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1.0.0",
                "jar",
                null,
                false));
    }
}
