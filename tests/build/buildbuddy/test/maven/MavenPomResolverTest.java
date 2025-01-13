package build.buildbuddy.test.maven;

import build.buildbuddy.maven.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MavenPomResolverTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path repository, project;

    private MavenPomResolver mavenPomResolver;

    @Before
    public void setUp() throws Exception {
        repository = temporaryFolder.newFolder("repository").toPath();
        project = temporaryFolder.newFolder("build/buildbuddy/project").toPath();
        mavenPomResolver = new MavenPomResolver(
                new MavenDefaultRepository(repository.toUri(), null, Map.of()),
                MavenDefaultVersionNegotiator.maven(new MavenDefaultRepository(repository.toUri(), null, Map.of())));
    }

    @Test
    public void can_resolve_dependencies() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_dependencies_with_property() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_dependencies_with_nested_property() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_dependency_without_pom() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_dependencies_with_duplicate() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_dependencies_from_parent() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("parent", "artifact", "1", """
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
        addToRepository("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_dependencies_from_parent_before_transitive() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("parent", "artifact", "1", """
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
        addToRepository("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("other", "artifact", "2", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("transitive", "artifact", "1", """
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                Map.entry(
                        new MavenDependencyKey("other", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("transitive", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_duplicate_dependencies_from_parent() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("parent", "artifact", "1", """
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
        addToRepository("other", "artifact", "2", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("2", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_transitive_dependencies() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("other", "artifact", "1", """
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
        addToRepository("transitive", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                Map.entry(
                        new MavenDependencyKey("other", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("transitive", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_transitive_dependencies_with_exclusion() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("other", "artifact", "1", """
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1",
                        MavenDependencyScope.COMPILE,
                        null,
                        List.of(new MavenDependencyName("transitive", "artifact")),
                        null)));
    }

    @Test
    public void can_resolve_transitive_dependencies_with_exclusion_wildcard() throws IOException {
        addToRepository("group", "artifact", "1", """
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
                                    <groupId>*</groupId>
                                    <artifactId>*</artifactId>
                                </exclusion>
                            </exclusions>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("other", "artifact", "1", """
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1",
                        MavenDependencyScope.COMPILE,
                        null,
                        List.of(MavenDependencyName.EXCLUDE_ALL),
                        null)));
    }

    @Test
    public void can_resolve_transitive_dependencies_with_optional() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("other", "artifact", "1", """
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_transitive_dependencies_with_scope() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("other", "artifact", "1", """
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
        addToRepository("transitive", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                Map.entry(
                        new MavenDependencyKey("other", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.TEST, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("transitive", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.TEST, null, null, null)));
    }

    @Test
    public void can_resolve_dependency_configuration() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void does_not_resolve_dependency_configuration_of_dependency() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("intermediate", "artifact", "1", """
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
                                <groupId>transitive</groupId>
                                <artifactId>artifact</artifactId>
                                <version>2</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        addToRepository("other", "artifact", "1", """
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
        addToRepository("transitive", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                Map.entry(
                        new MavenDependencyKey("intermediate", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("other", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("transitive", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_dependency_configuration_explicit_version() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_dependency_bom_configuration() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("import", "artifact", "1", """
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_dependency_bom_configuration_picks_first_import() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("import", "artifact", "1", """
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
        addToRepository("other-import", "artifact", "1", """
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_dependency_bom_configuration_but_prefer_dependency_configuration() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("import", "artifact", "1", """
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_lowest_depth_version() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("deep", "artifact", "1", """
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
        addToRepository("intermediate", "artifact", "1", """
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
        addToRepository("transitive", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("shallow", "artifact", "1", """
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                Map.entry(
                        new MavenDependencyKey("deep", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("shallow", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("intermediate", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("transitive", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_lowest_depth_version_with_scope_override() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("deep", "artifact", "1", """
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
        addToRepository("intermediate", "artifact", "1", """
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
        addToRepository("transitive", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("shallow", "artifact", "1", """
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                Map.entry(
                        new MavenDependencyKey("deep", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("shallow", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.TEST, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("intermediate", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("transitive", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_lowest_depth_version_with_scope_override_and_nested_transitives() throws IOException {
        addToRepository("group", "artifact", "1", """
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
        addToRepository("deep", "artifact", "1", """
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
        addToRepository("intermediate", "artifact", "1", """
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
        addToRepository("transitive", "artifact", "1", """
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
        addToRepository("nested", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("shallow", "artifact", "1", """
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                Map.entry(
                        new MavenDependencyKey("deep", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("shallow", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.TEST, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("intermediate", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("transitive", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("nested", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_release_version() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>RELEASE</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("transitive", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        Files.writeString(Files
                .createDirectories(repository.resolve("transitive/artifact/"))
                .resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata modelVersion="1.1.0">
                  <versioning>
                    <latest>2</latest>
                    <release>1</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("transitive", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_latest_version() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>LATEST</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("transitive", "artifact", "2", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        Files.writeString(Files
                .createDirectories(repository.resolve("transitive/artifact/"))
                .resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata modelVersion="1.1.0">
                  <versioning>
                    <latest>2</latest>
                    <release>1</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("transitive", "artifact", "jar", null),
                new MavenDependencyValue("2", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_closed_range_version() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[1,2]</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("transitive", "artifact", "2", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        Files.writeString(Files
                .createDirectories(repository.resolve("transitive/artifact/"))
                .resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata modelVersion="1.1.0">
                  <versioning>
                    <latest>2</latest>
                    <release>1</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("transitive", "artifact", "jar", null),
                new MavenDependencyValue("2", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_open_range_version() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>(1,3)</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("transitive", "artifact", "2", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        Files.writeString(Files
                .createDirectories(repository.resolve("transitive/artifact/"))
                .resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata modelVersion="1.1.0">
                  <versioning>
                    <latest>2</latest>
                    <release>1</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("transitive", "artifact", "jar", null),
                new MavenDependencyValue("2", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_range_over_version() throws IOException {
        addToRepository("group", "artifact", "1", """
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
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("transitive", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[2]</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("transitive", "artifact", "2", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        Files.writeString(Files
                .createDirectories(repository.resolve("transitive/artifact/"))
                .resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata modelVersion="1.1.0">
                  <versioning>
                    <latest>2</latest>
                    <release>2</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                Map.entry(
                        new MavenDependencyKey("transitive", "artifact", "jar", null),
                        new MavenDependencyValue("2", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("other", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_divergent_ranges() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>first</groupId>
                            <artifactId>artifact</artifactId>
                            <version>2</version>
                        </dependency>
                        <dependency>
                            <groupId>second</groupId>
                            <artifactId>artifact</artifactId>
                            <version>2</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("first", "artifact", "2", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                        <dependency>
                            <groupId>second</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[1]</version>
                        </dependency>
                </project>
                """);
        addToRepository("second", "artifact", "2", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                        <dependency>
                            <groupId>first</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[1]</version>
                        </dependency>
                </project>
                """);
        addToRepository("first", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("second", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        Files.writeString(Files
                .createDirectories(repository.resolve("first/artifact/"))
                .resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata modelVersion="1.1.0">
                  <versioning>
                    <latest>2</latest>
                    <release>2</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        Files.writeString(Files
                .createDirectories(repository.resolve("second/artifact/"))
                .resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata modelVersion="1.1.0">
                  <versioning>
                    <latest>2</latest>
                    <release>2</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies("group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                Map.entry(
                        new MavenDependencyKey("first", "artifact", "jar", null),
                        new MavenDependencyValue("2", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("second", "artifact", "jar", null),
                        new MavenDependencyValue("2", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_local_pom() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>project</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <build>
                        <sourceDirectory>sources</sourceDirectory>
                        <resources>
                            <resource>resource-1</resource>
                            <resource>resource-2</resource>
                        </resources>
                        <testSourceDirectory>tests</testSourceDirectory>
                        <testResources>
                            <testResource>testResource-1</testResource>
                            <testResource>testResource-2</testResource>
                        </testResources>
                    </build>
                    <dependencies>
                        <dependency>
                            <groupId>group</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
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
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(project);
        assertThat(poms).containsOnlyKeys(Path.of(""));
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.groupId()).isEqualTo("build/buildbuddy/project");
        assertThat(pom.artifactId()).isEqualTo("artifact");
        assertThat(pom.version()).isEqualTo("1");
        assertThat(pom.sourceDirectory()).isEqualTo("sources");
        assertThat(pom.resourceDirectories()).containsExactly("resource-1", "resource-2");
        assertThat(pom.testSourceDirectory()).isEqualTo("tests");
        assertThat(pom.testResourceDirectories()).containsExactly("testResource-1", "testResource-2");
        assertThat(pom.dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(pom.managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_local_pom_parent() throws IOException {
        Path subproject = Files.createDirectory(project.resolve("subproject"));
        Files.writeString(subproject.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>artifact</artifactId>
                    <parent>
                        <groupId>project</groupId>
                        <artifactId>parent</artifactId>
                        <version>1</version>
                    </parent>
                    <dependencies>
                        <dependency>
                            <groupId>group</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
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
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>project</groupId>
                    <artifactId>parent</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>group</groupId>
                            <artifactId>parent</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>parent</artifactId>
                                <version>1</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(subproject);
        assertThat(poms).containsOnlyKeys(Path.of(""));
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.groupId()).isEqualTo("build/buildbuddy/project");
        assertThat(pom.artifactId()).isEqualTo("artifact");
        assertThat(pom.version()).isEqualTo("1");
        assertThat(pom.dependencies()).containsExactly(
                Map.entry(
                        new MavenDependencyKey("group", "parent", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("group", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(pom.managedDependencies()).containsExactly(
                Map.entry(
                        new MavenDependencyKey("other", "parent", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("other", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_local_pom_parent_explicit_location() throws IOException {
        Path subproject = Files.createDirectory(project.resolve("subproject"));
        Files.writeString(subproject.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <artifactId>artifact</artifactId>
                    <parent>
                        <groupId>project</groupId>
                        <artifactId>parent</artifactId>
                        <version>1</version>
                        <relativePath>../parent/pom.xml</relativePath>
                    </parent>
                    <dependencies>
                        <dependency>
                            <groupId>group</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
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
        Files.writeString(Files.createDirectory(project.resolve("parent")).resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>project</groupId>
                    <artifactId>parent</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>group</groupId>
                            <artifactId>parent</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>parent</artifactId>
                                <version>1</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(subproject);
        assertThat(poms).containsOnlyKeys(Path.of(""));
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.groupId()).isEqualTo("build/buildbuddy/project");
        assertThat(pom.artifactId()).isEqualTo("artifact");
        assertThat(pom.version()).isEqualTo("1");
        assertThat(pom.dependencies()).containsExactly(
                Map.entry(
                        new MavenDependencyKey("group", "parent", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("group", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(pom.managedDependencies()).containsExactly(
                Map.entry(
                        new MavenDependencyKey("other", "parent", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("other", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_local_pom_repository_parent() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>project</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <parent>
                        <groupId>parent</groupId>
                        <artifactId>artifact</artifactId>
                        <version>1</version>
                    </parent>
                </project>
                """);
        addToRepository("parent", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>group</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
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
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(project);
        assertThat(poms).containsOnlyKeys(Path.of(""));
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(pom.managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_local_pom_repository_parent_on_mismatch() throws IOException {
        Path subproject = Files.createDirectory(project.resolve("subproject"));
        Files.writeString(subproject.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>project</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <parent>
                        <groupId>parent</groupId>
                        <artifactId>artifact</artifactId>
                        <version>1</version>
                    </parent>
                </project>
                """);
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>mismatch</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        addToRepository("parent", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>group</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
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
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(subproject);
        assertThat(poms).containsOnlyKeys(Path.of(""));
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(pom.managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_local_pom_parent_on_match() throws IOException {
        Path subproject = Files.createDirectory(project.resolve("subproject"));
        Files.writeString(subproject.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>project</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <parent>
                        <groupId>parent</groupId>
                        <artifactId>artifact</artifactId>
                        <version>1</version>
                    </parent>
                </project>
                """);
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>parent</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>group</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
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
        addToRepository("parent", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(subproject);
        assertThat(poms).containsOnlyKeys(Path.of(""));
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(pom.managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_repository_parent_if_specified() throws IOException {
        Path subproject = Files.createDirectory(project.resolve("subproject"));
        Files.writeString(subproject.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>project</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <parent>
                        <groupId>parent</groupId>
                        <artifactId>artifact</artifactId>
                        <version>1</version>
                        <relativePath/>
                    </parent>
                </project>
                """);
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>parent</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        addToRepository("parent", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>group</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
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
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(subproject);
        assertThat(poms).containsOnlyKeys(Path.of(""));
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(pom.managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_sub_modules() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>parent</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <modules>
                      <module>subproject</module>
                    </modules>
                    <dependencies>
                        <dependency>
                            <groupId>group</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
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
        Path subproject = Files.createDirectory(project.resolve("subproject"));
        Files.writeString(subproject.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>project</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <parent>
                        <groupId>parent</groupId>
                        <artifactId>artifact</artifactId>
                        <version>1</version>
                    </parent>
                </project>
                """);
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(project);
        assertThat(poms).containsOnlyKeys(Path.of(""), Path.of("subproject"));
        assertThat(poms.get(Path.of("")).dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(poms.get(Path.of("")).managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(poms.get(Path.of("subproject")).dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(poms.get(Path.of("subproject")).managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_sub_modules_reverse_location() throws IOException {
        Path subproject = Files.createDirectory(project.resolve("subproject"));
        Files.writeString(subproject.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>parent</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <modules>
                      <module>..</module>
                    </modules>
                    <dependencies>
                        <dependency>
                            <groupId>group</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
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
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>project</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <parent>
                        <groupId>parent</groupId>
                        <artifactId>artifact</artifactId>
                        <version>1</version>
                        <relativePath>subproject/pom.xml</relativePath>
                    </parent>
                </project>
                """);
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(subproject);
        assertThat(poms).containsOnlyKeys(Path.of(""), Path.of(".."));
        assertThat(poms.get(Path.of("")).dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(poms.get(Path.of("")).managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(poms.get(Path.of("..")).dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(poms.get(Path.of("..")).managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_detect_circular_modules() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>parent</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <modules>
                      <module>subproject</module>
                    </modules>
                </project>
                """);
        Path subproject = Files.createDirectory(project.resolve("subproject"));
        Files.writeString(subproject.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>project</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <parent>
                        <groupId>parent</groupId>
                        <artifactId>artifact</artifactId>
                        <version>1</version>
                    </parent>
                    <modules>
                      <module>..</module>
                    </modules>
                </project>
                """);
        assertThatThrownBy(() -> mavenPomResolver.local(project))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Circular POM module reference to ");
    }

    private void addToRepository(String groupId, String artifactId, String version, String pom) throws IOException {
        Files.writeString(Files
                .createDirectories(repository.resolve(groupId + "/" + artifactId + "/" + version))
                .resolve(artifactId + "-" + version + ".pom"), pom);
    }
}
