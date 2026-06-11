package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.DependencyScope;
import build.jenesis.License;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenDefaultVersionNegotiator;
import build.jenesis.maven.MavenDependencyKey;
import build.jenesis.maven.MavenDependencyName;
import build.jenesis.maven.MavenDependencyScope;
import build.jenesis.maven.MavenDependencyValue;
import build.jenesis.maven.MavenLocalPom;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenRepository;
import build.jenesis.maven.MavenResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MavenPomResolverTest {


    @TempDir
    private Path repository, project;
    private MavenRepository mavenRepository;
    private MavenPomResolver mavenPomResolver;

    @BeforeEach
    public void setUp() throws Exception {
        mavenRepository = new MavenDefaultRepository(repository.toUri(), repository, Map.of(), _ -> {});
        mavenPomResolver = new MavenPomResolver(MavenDefaultVersionNegotiator.maven());
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void test_jar_type_normalizes_to_tests_classifier() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                            <type>test-jar</type>
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", "tests"),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void explicit_classifier_on_test_jar_type_is_preserved() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                            <type>test-jar</type>
                            <classifier>fixtures</classifier>
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", "fixtures"),
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_dependencies_with_property_name_containing_dash() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <properties>
                        <version.org-json>1</version.org-json>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>${version.org-json}</version>
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void managed_dep_without_scope_does_not_override_transitive_test_scope() throws IOException {
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
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>inner</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        addToRepository("intermediate", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>inner</groupId>
                            <artifactId>artifact</artifactId>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("inner", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("intermediate", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void undefined_property_in_unconsumed_managed_dependency_is_tolerated() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>unused</groupId>
                                <artifactId>managed</artifactId>
                                <version>${undefined.property}</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void undefined_managed_version_applied_to_a_consumed_dependency_still_fails() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>artifact</artifactId>
                                <version>${undefined.property}</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);
        assertThatThrownBy(() -> mavenPomResolver.dependencies(
                Runnable::run, mavenRepository, "group", "artifact", "1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to resolve other:artifact:${undefined.property}");
    }

    @Test
    public void undefined_property_in_a_consumed_dependency_still_fails() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>${undefined.property}</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        assertThatThrownBy(() -> mavenPomResolver.dependencies(
                Runnable::run, mavenRepository, "group", "artifact", "1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to resolve other:artifact:${undefined.property}");
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
    public void scope_with_surrounding_whitespace_is_normalized() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                            <scope>
                                runtime
                            </scope>
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.RUNTIME, null, null, null)));
    }

    @Test
    public void unknown_scope_is_reported_with_offending_value() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                            <scope>bogus</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);
        assertThatThrownBy(() -> mavenPomResolver.dependencies(
                Runnable::run, mavenRepository, "group", "artifact", "1", null))
                .hasStackTraceContaining("Unknown Maven dependency scope")
                .hasStackTraceContaining("bogus");
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_dependency_bom_configuration_flattens_nested_import() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>leaf</groupId>
                            <artifactId>artifact</artifactId>
                        </dependency>
                    </dependencies>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>aggregator</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        addToRepository("aggregator", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>nested</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        addToRepository("nested", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>leaf</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        addToRepository("leaf", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("leaf", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_dependency_bom_configuration_flattens_nested_import_with_own_properties() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>leaf</groupId>
                            <artifactId>artifact</artifactId>
                        </dependency>
                    </dependencies>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>aggregator</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        addToRepository("aggregator", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>nested</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                                <type>pom</type>
                                <scope>import</scope>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        addToRepository("nested", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <properties>
                        <leaf.version>1</leaf.version>
                    </properties>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>leaf</groupId>
                                <artifactId>artifact</artifactId>
                                <version>${leaf.version}</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        addToRepository("leaf", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("leaf", "artifact", "jar", null),
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("transitive", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_release_version_with_legacy_metadata_modelversion() throws IOException {
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
                <metadata>
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("transitive", "artifact", "jar", null),
                new MavenDependencyValue("2", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void reports_discovered_range_and_negotiated_version() throws IOException {
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
        addJarToRepository("group", "artifact", "1");
        addJarToRepository("transitive", "artifact", "2");
        Resolver.Resolution resolution = mavenPomResolver.dependencies(
                Runnable::run,
                "maven",
                Map.<String, Repository>of("maven", mavenRepository),
                new LinkedHashMap<>(Map.of("group/artifact/1", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE);
        List<String> followed = resolution.edges().stream()
                .filter(Resolver.Edge::followed)
                .map(Resolver.Edge::coordinate)
                .toList();
        assertThat(followed).containsExactly("maven/group/artifact/1", "maven/transitive/artifact/[1,2]");
        assertThat(resolution.vertices().get("maven/transitive/artifact").resolvedVersion()).isEqualTo("2");
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
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
    public void can_resolve_unbounded_upper_range_version() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[2,)</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("transitive", "artifact", "3", """
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
                    <latest>3</latest>
                    <release>3</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                      <version>3</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("transitive", "artifact", "jar", null),
                new MavenDependencyValue("3", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_unbounded_lower_range_version() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>(,2]</version>
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
                    <latest>3</latest>
                    <release>3</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                      <version>3</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("transitive", "artifact", "jar", null),
                new MavenDependencyValue("2", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_exclusive_upper_range_version() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[1,3)</version>
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
                    <latest>3</latest>
                    <release>3</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                      <version>3</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("transitive", "artifact", "jar", null),
                new MavenDependencyValue("2", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_multi_range_version() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[1,2),[3,)</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("transitive", "artifact", "4", """
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
                    <latest>4</latest>
                    <release>4</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                      <version>3</version>
                      <version>4</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("transitive", "artifact", "jar", null),
                new MavenDependencyValue("4", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_multi_range_version_with_gap() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[1,2],[4,5]</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("transitive", "artifact", "5", """
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
                    <latest>6</latest>
                    <release>6</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                      <version>3</version>
                      <version>4</version>
                      <version>5</version>
                      <version>6</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("transitive", "artifact", "jar", null),
                new MavenDependencyValue("5", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_intersecting_ranges() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>first</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                        <dependency>
                            <groupId>second</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("first", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[1,3]</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("second", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[2,4]</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("transitive", "artifact", "3", """
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
                    <latest>4</latest>
                    <release>4</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                      <version>3</version>
                      <version>4</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                Map.entry(
                        new MavenDependencyKey("first", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("second", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("transitive", "artifact", "jar", null),
                        new MavenDependencyValue("3", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void cannot_resolve_disjoint_ranges() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>first</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                        <dependency>
                            <groupId>second</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("first", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[1,1]</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("second", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[3,3]</version>
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
        addToRepository("transitive", "artifact", "3", """
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
                    <latest>3</latest>
                    <release>3</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                      <version>3</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        assertThatThrownBy(() -> mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not resolve version");
    }

    @Test
    public void cannot_resolve_range_with_no_matching_version() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>(1,)</version>
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
                    <latest>1</latest>
                    <release>1</release>
                    <versions>
                      <version>1</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        assertThatThrownBy(() -> mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not resolve version in range");
    }

    @Test
    public void can_resolve_hard_requirement_over_soft_requirement() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>dep</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>2</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("dep", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[1]</version>
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
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                Map.entry(
                        new MavenDependencyKey("dep", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("transitive", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void only_reports_the_converged_graph() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>mid</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                        <dependency>
                            <groupId>forcer</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("mid", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>conflict</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("forcer", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>conflict</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[2]</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("conflict", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>childone</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("conflict", "artifact", "2", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>childtwo</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("childone", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("childtwo", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        Files.writeString(Files
                .createDirectories(repository.resolve("conflict/artifact/"))
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
        addJarToRepository("group", "artifact", "1");
        addJarToRepository("mid", "artifact", "1");
        addJarToRepository("forcer", "artifact", "1");
        addJarToRepository("conflict", "artifact", "2");
        addJarToRepository("childtwo", "artifact", "1");
        Resolver.Resolution resolution = mavenPomResolver.dependencies(
                Runnable::run,
                "maven",
                Map.<String, Repository>of("maven", mavenRepository),
                new LinkedHashMap<>(Map.of("group/artifact/1", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE);
        List<String> followedCoordinates = resolution.edges().stream()
                .filter(Resolver.Edge::followed)
                .map(Resolver.Edge::coordinate)
                .toList();
        assertThat(followedCoordinates).contains("maven/childtwo/artifact/1");
        assertThat(followedCoordinates).doesNotContain("maven/childone/artifact/1");
    }

    @Test
    public void can_resolve_range_over_soft_requirement_in_transitive_dependencies() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>first</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                        <dependency>
                            <groupId>second</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("first", "artifact", "1", """
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
        addToRepository("second", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>3</version>
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
        addToRepository("transitive", "artifact", "3", """
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
                    <latest>3</latest>
                    <release>3</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                      <version>3</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                Map.entry(
                        new MavenDependencyKey("first", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("second", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("transitive", "artifact", "jar", null),
                        new MavenDependencyValue("2", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_direct_soft_with_transitive_range() throws IOException {
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
                            <groupId>dep</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("dep", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[2,3]</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("transitive", "artifact", "3", """
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
                    <latest>3</latest>
                    <release>3</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                      <version>3</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                Map.entry(
                        new MavenDependencyKey("transitive", "artifact", "jar", null),
                        new MavenDependencyValue("3", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("dep", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_direct_range_with_transitive_soft() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[2,3]</version>
                        </dependency>
                        <dependency>
                            <groupId>dep</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("dep", "artifact", "1", """
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
        addToRepository("transitive", "artifact", "3", """
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
                    <latest>3</latest>
                    <release>3</release>
                    <versions>
                      <version>1</version>
                      <version>2</version>
                      <version>3</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(
                Map.entry(
                        new MavenDependencyKey("transitive", "artifact", "jar", null),
                        new MavenDependencyValue("3", MavenDependencyScope.COMPILE, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("dep", "artifact", "jar", null),
                        new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_range_over_qualifier_versions() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[1.0-alpha,1.0]</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("transitive", "artifact", "1.0", """
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
                    <latest>1.0.1</latest>
                    <release>1.0.1</release>
                    <versions>
                      <version>1.0-alpha</version>
                      <version>1.0-beta</version>
                      <version>1.0-rc1</version>
                      <version>1.0-SNAPSHOT</version>
                      <version>1.0</version>
                      <version>1.0.1</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("transitive", "artifact", "jar", null),
                new MavenDependencyValue("1.0", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void can_resolve_range_with_multi_segment_versions() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>transitive</groupId>
                            <artifactId>artifact</artifactId>
                            <version>[1.0,2.0]</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("transitive", "artifact", "2.0", """
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
                    <latest>3.0</latest>
                    <release>3.0</release>
                    <versions>
                      <version>1.0</version>
                      <version>1.5</version>
                      <version>2.0</version>
                      <version>3.0</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> dependencies = mavenPomResolver.dependencies(
                Runnable::run,
                mavenRepository,
                "group",
                "artifact",
                "1",
                null);
        assertThat(dependencies).containsExactly(Map.entry(
                new MavenDependencyKey("transitive", "artifact", "jar", null),
                new MavenDependencyValue("2.0", MavenDependencyScope.COMPILE, null, null, null)));
    }

    @Test
    public void multiple_checksum_comments_on_one_dependency_fail() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>project</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>group</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                                <!--Checksum/SHA256/cafebabe-->
                                <!--Checksum/SHA256/deadbeef-->
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        assertThatThrownBy(() -> mavenPomResolver.local(Runnable::run, mavenRepository, project))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple Checksum/* comments")
                .hasMessageContaining("group:artifact:1");
    }

    @Test
    public void local_pom_dependency_management_checksum_is_honored() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>project</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>group</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                                <!--Checksum/SHA256/cafebabe-->
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>group</groupId>
                            <artifactId>artifact</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(Runnable::run, mavenRepository, project);
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.dependencies().get(new MavenDependencyKey("group", "artifact", "jar", null)).checksum())
                .isEqualTo("SHA256/cafebabe");
    }

    @Test
    public void local_pom_direct_dependency_checksum_is_ignored() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>project</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>group</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                            <!--Checksum/SHA256/cafebabe-->
                        </dependency>
                    </dependencies>
                </project>
                """);
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(Runnable::run, mavenRepository, project);
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.dependencies().get(new MavenDependencyKey("group", "artifact", "jar", null)).checksum())
                .isNull();
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
                            <resource>
                                <directory>resource-1</directory>
                            </resource>
                            <resource>
                                <directory>resource-2</directory>
                            </resource>
                        </resources>
                        <testSourceDirectory>tests</testSourceDirectory>
                        <testResources>
                            <testResource>
                                <directory>testResource-1</directory>
                            </testResource>
                            <testResource>
                                <directory>testResource-2</directory>
                            </testResource>
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
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(Runnable::run, mavenRepository, project);
        assertThat(poms).containsOnlyKeys(Path.of(""));
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.groupId()).isEqualTo("project");
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
                new MavenDependencyValue("1", null, null, null, null)));
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
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(Runnable::run, mavenRepository, subproject);
        assertThat(poms).containsOnlyKeys(Path.of(""));
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.groupId()).isEqualTo("project");
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
                        new MavenDependencyValue("1", null, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("other", "artifact", "jar", null),
                        new MavenDependencyValue("1", null, null, null, null)));
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
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(Runnable::run, mavenRepository, subproject);
        assertThat(poms).containsOnlyKeys(Path.of(""));
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.groupId()).isEqualTo("project");
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
                        new MavenDependencyValue("1", null, null, null, null)),
                Map.entry(
                        new MavenDependencyKey("other", "artifact", "jar", null),
                        new MavenDependencyValue("1", null, null, null, null)));
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
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(Runnable::run, mavenRepository, project);
        assertThat(poms).containsOnlyKeys(Path.of(""));
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(pom.managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", null, null, null, null)));
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
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(Runnable::run, mavenRepository, subproject);
        assertThat(poms).containsOnlyKeys(Path.of(""));
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(pom.managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", null, null, null, null)));
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
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(Runnable::run, mavenRepository, subproject);
        assertThat(poms).containsOnlyKeys(Path.of(""));
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(pom.managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", null, null, null, null)));
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
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(Runnable::run, mavenRepository, subproject);
        assertThat(poms).containsOnlyKeys(Path.of(""));
        MavenLocalPom pom = poms.get(Path.of(""));
        assertThat(pom.dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(pom.managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", null, null, null, null)));
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
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(Runnable::run, mavenRepository, project);
        assertThat(poms).containsOnlyKeys(Path.of(""), Path.of("subproject"));
        assertThat(poms.get(Path.of("")).dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(poms.get(Path.of("")).managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", null, null, null, null)));
        assertThat(poms.get(Path.of("subproject")).dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(poms.get(Path.of("subproject")).managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", null, null, null, null)));
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
        SequencedMap<Path, MavenLocalPom> poms = mavenPomResolver.local(Runnable::run, mavenRepository, subproject);
        assertThat(poms).containsOnlyKeys(Path.of(""), Path.of(".."));
        assertThat(poms.get(Path.of("")).dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(poms.get(Path.of("")).managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", null, null, null, null)));
        assertThat(poms.get(Path.of("..")).dependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("group", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null)));
        assertThat(poms.get(Path.of("..")).managedDependencies()).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", null, null, null, null)));
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
        assertThatThrownBy(() -> mavenPomResolver.local(Runnable::run, mavenRepository, project))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Circular POM module reference to ");
    }

    @Test
    public void captures_declared_and_parent_inherited_licenses() throws IOException {
        addToRepository("parentgroup", "parentlib", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>parentgroup</groupId>
                    <artifactId>parentlib</artifactId>
                    <version>1</version>
                    <licenses>
                        <license>
                            <name>Apache-2.0</name>
                            <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
                        </license>
                    </licenses>
                </project>
                """);
        addToRepository("leafgroup", "leaflib", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>parentgroup</groupId>
                        <artifactId>parentlib</artifactId>
                        <version>1</version>
                    </parent>
                    <artifactId>leaflib</artifactId>
                </project>
                """);
        addToRepository("dirgroup", "dirlib", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>dirgroup</groupId>
                    <artifactId>dirlib</artifactId>
                    <version>1</version>
                    <licenses>
                        <license>
                            <name>MIT</name>
                            <url>https://opensource.org/license/mit</url>
                        </license>
                    </licenses>
                </project>
                """);
        addToRepository("rootgroup", "rootlib", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>rootgroup</groupId>
                    <artifactId>rootlib</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>leafgroup</groupId>
                            <artifactId>leaflib</artifactId>
                            <version>1</version>
                        </dependency>
                        <dependency>
                            <groupId>dirgroup</groupId>
                            <artifactId>dirlib</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);

        addJarToRepository("rootgroup", "rootlib", "1");
        addJarToRepository("leafgroup", "leaflib", "1");
        addJarToRepository("dirgroup", "dirlib", "1");
        Resolver.Resolution resolution = mavenPomResolver.dependencies(
                Runnable::run,
                "maven",
                Map.<String, Repository>of("maven", mavenRepository),
                new LinkedHashMap<>(Map.of("rootgroup/rootlib/1", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE);

        assertThat(resolution.vertices().get("maven/leafgroup/leaflib").licenses())
                .as("a dependency inherits its parent POM's license")
                .containsExactly(new License("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0.txt"));
        assertThat(resolution.vertices().get("maven/dirgroup/dirlib").licenses())
                .as("a dependency's own declared license is captured")
                .containsExactly(new License("MIT", "https://opensource.org/license/mit"));
    }

    private void addToRepository(String groupId, String artifactId, String version, String pom) throws IOException {
        Files.writeString(Files
                .createDirectories(repository.resolve(groupId + "/" + artifactId + "/" + version))
                .resolve(artifactId + "-" + version + ".pom"), pom);
    }

    private void addJarToRepository(String groupId, String artifactId, String version) throws IOException {
        addJarToRepository(groupId, artifactId, version, null);
    }

    private void addJarToRepository(String groupId, String artifactId, String version, String classifier) throws IOException {
        Files.write(Files
                        .createDirectories(repository.resolve(groupId + "/" + artifactId + "/" + version))
                        .resolve(artifactId + "-" + version + (classifier == null ? "" : "-" + classifier) + ".jar"),
                (groupId + ":" + artifactId + ":" + version).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void external_managed_dep_checksum_is_ignored() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                                <!--Checksum/SHA256/cafebabe-->
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        SequencedMap<MavenDependencyKey, MavenDependencyValue> deps = mavenPomResolver.dependencies(
                Runnable::run, mavenRepository, "group", "artifact", "1", null);
        assertThat(deps).containsExactly(Map.entry(
                new MavenDependencyKey("other", "artifact", "jar", null),
                new MavenDependencyValue("1", MavenDependencyScope.COMPILE, null, null, null, null)));
    }

    @Test
    public void first_party_managed_dep_checksum_propagates_to_resolved_value() throws IOException {
        addToRepository("other", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        String rootPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>other</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                                <!--Checksum/SHA256/cafebabe-->
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """;
        SequencedMap<MavenDependencyKey, MavenDependencyValue> deps = mavenPomResolver.dependencies(
                Runnable::run, mavenRepository,
                List.of(new MavenResolver.RootPom(new ByteArrayInputStream(rootPom.getBytes(StandardCharsets.UTF_8)))),
                List.of(),
                MavenDependencyScope.COMPILE,
                "main").dependencies();
        assertThat(deps.get(new MavenDependencyKey("other", "artifact", "jar", null)).checksum())
                .isEqualTo("SHA256/cafebabe");
    }

    @Test
    public void parent_pom_checksum_is_ignored() throws IOException {
        addToRepository("parent", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>parent</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>parent</groupId>
                        <artifactId>artifact</artifactId>
                        <version>1</version>
                        <relativePath/>
                        <!--Checksum/SHA-256/deadbeef-->
                    </parent>
                    <artifactId>child</artifactId>
                </project>
                """);
        assertThatCode(() -> mavenPomResolver.local(
                Runnable::run, mavenRepository, project)).doesNotThrowAnyException();
    }

    @Test
    public void external_bom_import_checksum_is_ignored() throws IOException {
        addToRepository("bom", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>bom</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                </project>
                """);
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>bom</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                                <type>pom</type>
                                <scope>import</scope>
                                <!--Checksum/SHA-256/cafebabe-->
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        assertThatCode(() -> mavenPomResolver.dependencies(
                Runnable::run, mavenRepository, "group", "artifact", "1", null)).doesNotThrowAnyException();
    }

    @Test
    public void first_party_bom_import_checksum_mismatch_fails_resolution() throws IOException {
        addToRepository("bom", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>bom</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                </project>
                """);
        String rootPom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>bom</groupId>
                                <artifactId>artifact</artifactId>
                                <version>1</version>
                                <type>pom</type>
                                <scope>import</scope>
                                <!--Checksum/SHA-256/cafebabe-->
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """;
        assertThatThrownBy(() -> mavenPomResolver.dependencies(
                Runnable::run, mavenRepository,
                List.of(new MavenResolver.RootPom(new ByteArrayInputStream(rootPom.getBytes(StandardCharsets.UTF_8)))),
                List.of(),
                MavenDependencyScope.COMPILE,
                "main"))
                .hasStackTraceContaining("Mismatched POM checksum")
                .hasStackTraceContaining("bom:artifact:1");
    }

    @Test
    public void spi_external_versions_pin_transitive() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
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
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>pinned</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("pinned", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("pinned", "artifact", "2", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addJarToRepository("group", "artifact", "1");
        addJarToRepository("other", "artifact", "1");
        addJarToRepository("pinned", "artifact", "2");
        SequencedMap<String, String> versions = new LinkedHashMap<>();
        versions.put("pinned/artifact/jar", "2");
        SequencedMap<String, Resolver.Resolved> resolved = mavenPomResolver.dependencies(
                Runnable::run,
                "maven",
                Map.of("maven", mavenRepository),
                new LinkedHashMap<>(Map.of("group/artifact/1", Collections.emptyNavigableSet())),
                versions,
                DependencyScope.COMPILE).artifacts();
        assertThat(resolved).containsOnlyKeys(
                "maven/group/artifact/1",
                "maven/other/artifact/1",
                "maven/pinned/artifact/2");
    }

    @Test
    public void spi_external_versions_pin_with_short_key_form() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>pinned</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("pinned", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("pinned", "artifact", "5", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addJarToRepository("group", "artifact", "1");
        addJarToRepository("pinned", "artifact", "5");
        SequencedMap<String, String> versions = new LinkedHashMap<>();
        versions.put("pinned/artifact", "5");
        SequencedMap<String, Resolver.Resolved> resolved = mavenPomResolver.dependencies(
                Runnable::run,
                "maven",
                Map.of("maven", mavenRepository),
                new LinkedHashMap<>(Map.of("group/artifact/1", Collections.emptyNavigableSet())),
                versions,
                DependencyScope.COMPILE).artifacts();
        assertThat(resolved).containsOnlyKeys(
                "maven/group/artifact/1",
                "maven/pinned/artifact/5");
    }

    @Test
    public void spi_external_versions_pin_with_classifier() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>pinned</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                            <classifier>sources</classifier>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("pinned", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("pinned", "artifact", "3", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addJarToRepository("group", "artifact", "1");
        addJarToRepository("pinned", "artifact", "3", "sources");
        SequencedMap<String, String> versions = new LinkedHashMap<>();
        versions.put("pinned/artifact/jar/sources", "3");
        SequencedMap<String, Resolver.Resolved> resolved = mavenPomResolver.dependencies(
                Runnable::run,
                "maven",
                Map.of("maven", mavenRepository),
                new LinkedHashMap<>(Map.of("group/artifact/1", Collections.emptyNavigableSet())),
                versions,
                DependencyScope.COMPILE).artifacts();
        assertThat(resolved).containsOnlyKeys(
                "maven/group/artifact/1",
                "maven/pinned/artifact/jar/sources/3");
    }

    @Test
    public void spi_empty_versions_does_not_change_resolution() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
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
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addJarToRepository("group", "artifact", "1");
        addJarToRepository("other", "artifact", "1");
        SequencedMap<String, Resolver.Resolved> resolved = mavenPomResolver.dependencies(
                Runnable::run,
                "maven",
                Map.of("maven", mavenRepository),
                new LinkedHashMap<>(Map.of("group/artifact/1", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE).artifacts();
        assertThat(resolved).containsOnlyKeys(
                "maven/group/artifact/1",
                "maven/other/artifact/1");
    }

    @Test
    public void spi_external_versions_pin_without_direct_dependency() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>middle</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("middle", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>deep</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("deep", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addToRepository("deep", "artifact", "7", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        addJarToRepository("group", "artifact", "1");
        addJarToRepository("middle", "artifact", "1");
        addJarToRepository("deep", "artifact", "7");
        SequencedMap<String, String> versions = new LinkedHashMap<>();
        versions.put("deep/artifact/jar", "7");
        SequencedMap<String, Resolver.Resolved> resolved = mavenPomResolver.dependencies(
                Runnable::run,
                "maven",
                Map.of("maven", mavenRepository),
                new LinkedHashMap<>(Map.of("group/artifact/1", Collections.emptyNavigableSet())),
                versions,
                DependencyScope.COMPILE).artifacts();
        assertThat(resolved).containsOnlyKeys(
                "maven/group/artifact/1",
                "maven/middle/artifact/1",
                "maven/deep/artifact/7");
    }

    @Test
    public void coordinate_key_rejects_traversal_component() {
        assertThatThrownBy(() -> new MavenDependencyKey("..", "artifact", "jar", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("..");
        assertThatThrownBy(() -> new MavenDependencyKey("group", "a/b", "jar", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a/b");
        assertThatThrownBy(() -> new MavenDependencyKey("group", "artifact", "ja\\r", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void parsed_coordinate_rejects_traversal_component() {
        assertThatThrownBy(() -> MavenDependencyKey.parseKey("group/.."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("..");
        assertThatThrownBy(() -> MavenDependencyKey.tryParse("group/artifact/.."))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("..");
    }

    @Test
    public void transitive_traversal_coordinate_is_rejected() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>..</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        assertThatThrownBy(() -> mavenPomResolver.dependencies(
                Runnable::run, mavenRepository, "group", "artifact", "1", null))
                .hasStackTraceContaining("Illegal Maven coordinate groupId");
    }

    @Test
    public void external_conflicting_checksums_are_ignored() throws IOException {
        addToRepository("group", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>left</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                        <dependency>
                            <groupId>right</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("left", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>shared</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                            <!--Checksum/SHA256/aaaa-->
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("right", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <dependencies>
                        <dependency>
                            <groupId>shared</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                            <!--Checksum/SHA256/bbbb-->
                        </dependency>
                    </dependencies>
                </project>
                """);
        addToRepository("shared", "artifact", "1", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """);
        assertThatCode(() -> mavenPomResolver.dependencies(
                Runnable::run, mavenRepository, "group", "artifact", "1", null)).doesNotThrowAnyException();
    }
}
