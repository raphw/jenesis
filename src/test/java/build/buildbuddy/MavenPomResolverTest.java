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
import java.util.Map;

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
        toFile("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1",
                "jar",
                null,
                MavenDependencyScope.COMPILE,
                null,
                false));
    }

    @Test
    public void can_resolve_dependencies_with_property() throws IOException {
        toFile("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <properties>
                        <my.version>1</my.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>${my.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1",
                "jar",
                null,
                MavenDependencyScope.COMPILE,
                null,
                false));
    }

    @Test
    public void can_resolve_dependencies_with_nested_property() throws IOException {
        toFile("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <properties>
                        <my.version>${intermediate.version}</my.version>
                        <intermediate.version>1</intermediate.version>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>${my.version}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1",
                "jar",
                null,
                MavenDependencyScope.COMPILE,
                null,
                false));
    }

    @Test
    public void can_resolve_dependencies_with_duplicate() throws IOException {
        toFile("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>2</version>
                        </dependency>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1",
                "jar",
                null,
                MavenDependencyScope.COMPILE,
                null,
                false));
    }

    @Test
    public void can_resolve_dependencies_from_parent() throws IOException {
        toFile("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>parent</groupId>
                        <artifactId>artifact</artifactId>
                        <version>1</version>
                    </parent>
                </project>
                """);
        toFile("parent", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1",
                "jar",
                null,
                MavenDependencyScope.COMPILE,
                null,
                false));
    }

    @Test
    public void can_resolve_dependencies_from_parent_before_transitive() throws IOException {
        toFile("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>parent</groupId>
                        <artifactId>artifact</artifactId>
                        <version>1</version>
                    </parent>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("parent", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        toFile("other", "artifact", "2", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        toFile("transitive", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>2</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                new MavenDependency("other",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false),
                new MavenDependency("transitive",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false));
    }

    @Test
    public void can_resolve_duplicate_dependencies_from_parent() throws IOException {
        toFile("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>parent</groupId>
                        <artifactId>artifact</artifactId>
                        <version>1</version>
                    </parent>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>2</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("parent", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("other", "artifact", "2", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        toFile("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "2",
                "jar",
                null,
                MavenDependencyScope.COMPILE,
                null,
                false));
    }

    @Test
    public void can_resolve_transitive_dependencies() throws IOException {
        toFile("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("other", "artifact", "1", """
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
                """);
        toFile("transitive", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                new MavenDependency("other",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false),
                new MavenDependency("transitive",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false));
    }

    @Test
    public void can_resolve_transitive_dependencies_with_exclusion() throws IOException {
        toFile("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
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
        toFile("other", "artifact", "1", """
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
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1",
                "jar",
                null,
                MavenDependencyScope.COMPILE,
                null,
                false));
    }

    @Test
    public void can_resolve_transitive_dependencies_with_optional() throws IOException {
        toFile("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                            <optional>true</optional>
                        </dependency>
                    </dependencies>
                </project>
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1",
                "jar",
                null,
                MavenDependencyScope.COMPILE,
                null,
                false));
    }

    @Test
    public void can_resolve_transitive_dependencies_with_scope() throws IOException {
        toFile("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                        <dependency>
                            <groupId>test</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("transitive", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                new MavenDependency("other",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.TEST,
                        null,
                        false),
                new MavenDependency("transitive",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.TEST,
                        null,
                        false));
    }

    @Test
    public void can_resolve_dependency_configuration() throws IOException {
        toFile("group", "artifact", "1", """
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
                                <version>1</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        toFile("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1",
                "jar",
                null,
                MavenDependencyScope.COMPILE,
                null,
                false));
    }

    @Test
    public void can_resolve_dependency_configuration_explicit_version() throws IOException {
        toFile("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>artifact</artifactId>
                                <version>2</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        toFile("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1",
                "jar",
                null,
                MavenDependencyScope.COMPILE,
                null,
                false));
    }

    @Test
    public void can_resolve_dependency_bom_configuration() throws IOException {
        toFile("group", "artifact", "1", """
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
                                <groupId>import</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        toFile("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        toFile("import", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1",
                "jar",
                null,
                MavenDependencyScope.COMPILE,
                null,
                false));
    }

    @Test
    public void can_resolve_dependency_bom_configuration_picks_first_import() throws IOException {
        toFile("group", "artifact", "1", """
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
                                <groupId>import</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                            <dependency>
                                <groupId>other-import</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        toFile("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        toFile("import", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        toFile("other-import", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>artifact</artifactId>
                                <version>2</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1",
                "jar",
                null,
                MavenDependencyScope.COMPILE,
                null,
                false));
    }

    @Test
    public void can_resolve_dependency_bom_configuration_but_prefer_dependency_configuration() throws IOException {
        toFile("group", "artifact", "1", """
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
                                <version>1</version>
                            </dependency>
                            <dependency>
                                <groupId>import</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        toFile("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        toFile("import", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>artifact</artifactId>
                                <version>2</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(new MavenDependency("other",
                "artifact",
                "1",
                "jar",
                null,
                MavenDependencyScope.COMPILE,
                null,
                false));
    }

    @Test
    public void can_resolve_lowest_depth_version() throws IOException {
        toFile("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>deep</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                        <dependency>
                            <groupId>shallow</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("deep", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>intermediate</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("intermediate", "artifact", "1", """
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
                """);
        toFile("transitive", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        toFile("shallow", "artifact", "1", """
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
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                new MavenDependency("deep",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false),
                new MavenDependency("shallow",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false),
                new MavenDependency("intermediate",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false),
                new MavenDependency("transitive",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false));
    }

    @Test
    public void can_resolve_lowest_depth_version_with_scope_override() throws IOException {
        toFile("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>deep</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                        <dependency>
                            <groupId>shallow</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("deep", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>intermediate</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("intermediate", "artifact", "1", """
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
                """);
        toFile("transitive", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        toFile("shallow", "artifact", "1", """
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
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                new MavenDependency("deep",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false),
                new MavenDependency("shallow",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.TEST,
                        null,
                        false),
                new MavenDependency("intermediate",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false),
                new MavenDependency("transitive",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false));
    }

    @Test
    public void can_resolve_lowest_depth_version_with_scope_override_and_nested_transitives() throws IOException {
        toFile("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>deep</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                        <dependency>
                            <groupId>shallow</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("deep", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>intermediate</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("intermediate", "artifact", "1", """
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
                """);
        toFile("transitive", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>nested</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        toFile("nested", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        toFile("shallow", "artifact", "1", """
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
                """);
        List<MavenDependency> dependencies = new MavenPomResolver(new MavenRepository(repository.toUri(),
                null,
                Map.of())).dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                new MavenDependency("deep",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false),
                new MavenDependency("shallow",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.TEST,
                        null,
                        false),
                new MavenDependency("intermediate",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false),
                new MavenDependency("transitive",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false),
                new MavenDependency("nested",
                        "artifact",
                        "1",
                        "jar",
                        null,
                        MavenDependencyScope.COMPILE,
                        null,
                        false));
    }

    private void toFile(String groupId, String artifactId, String version, String pom) throws IOException {
        try (Writer writer = Files.newBufferedWriter(Files
                .createDirectories(repository.resolve(groupId + "/" + artifactId + "/" + version))
                .resolve(artifactId + "-" + version + ".pom"))) {
            writer.write(pom);
        }
    }
}
