package build.buildbuddy.test.maven;

import build.buildbuddy.*;
import build.buildbuddy.maven.*;
import build.buildbuddy.project.JavaModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.SequencedMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class MavenProjectTest {

    @TempDir
    private Path project, build, repository;
    private MavenRepository mavenRepository;
    private MavenPomResolver mavenPomResolver;

    @BeforeEach
    public void setUp() throws Exception {
        mavenRepository = new MavenDefaultRepository(repository.toUri(),
                null,
                Map.of());
        mavenPomResolver = new MavenPomResolver(MavenDefaultVersionNegotiator.maven());
    }

    @Test
    public void can_resolve_pom() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("src/main/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("src/test/java")).resolve("source"), "bar");
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module/module-/declare", "maven/module/test-module-/declare");
        Path module = results.get("maven/module/module-/declare");
        assertThat(module.resolve(BuildStep.COORDINATES)).exists();
        Properties coordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(module.resolve(BuildStep.COORDINATES))) {
            coordinates.load(reader);
        }
        assertThat(coordinates).containsOnlyKeys(
                "maven/group/artifact/jar/1",
                "maven/group/artifact/pom/1");
        assertThat(coordinates.getProperty("maven/group/artifact/jar/1")).isEmpty();
        assertThat(module.resolve(BuildStep.DEPENDENCIES)).exists();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(module.resolve(BuildStep.DEPENDENCIES))) {
            dependencies.load(reader);
        }
        assertThat(dependencies).containsOnlyKeys("maven/other/artifact/jar/1");
        assertThat(dependencies.getProperty("maven/other/artifact/jar/1")).isEmpty();
        Path testModule = results.get("maven/module/test-module-/declare");
        assertThat(testModule.resolve(BuildStep.COORDINATES)).exists();
        Properties testCoordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(testModule.resolve(BuildStep.COORDINATES))) {
            testCoordinates.load(reader);
        }
        assertThat(testCoordinates).containsOnlyKeys(
                "maven/group/artifact/jar/tests/1",
                "maven/group/artifact/pom/1");
        assertThat(testCoordinates.getProperty("maven/group/artifact/jar/tests/1")).isEmpty();
        assertThat(testModule.resolve(BuildStep.DEPENDENCIES)).exists();
        Properties testDependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(testModule.resolve(BuildStep.DEPENDENCIES))) {
            testDependencies.load(reader);
        }
        assertThat(testDependencies).containsOnlyKeys("maven/group/artifact/jar/1");
        assertThat(testDependencies.getProperty("maven/group/artifact/jar/1")).isEmpty();
    }

    @Test
    public void can_resolve_multi_pom() throws IOException {
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
        Files.writeString(Files.createDirectories(project.resolve("src/main/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("src/test/java")).resolve("source"), "bar");
        Path subproject = Files.createDirectory(project.resolve("subproject"));
        Files.writeString(subproject.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                        <parent>
                        <groupId>parent</groupId>
                        <artifactId>artifact</artifactId>
                        <version>1</version>
                    </parent>
                </project>
                """);
        Files.writeString(Files.createDirectories(subproject.resolve("src/main/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(subproject.resolve("src/test/java")).resolve("source"), "bar");
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module/module-/declare", "maven/module/module-subproject/declare");
        Path parent = results.get("maven/module/module-/declare");
        assertThat(parent.resolve(BuildStep.COORDINATES)).exists();
        Properties parentCoordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(parent.resolve(BuildStep.COORDINATES))) {
            parentCoordinates.load(reader);
        }
        assertThat(parentCoordinates).containsOnlyKeys(
                "maven/parent/artifact/jar/1",
                "maven/parent/artifact/pom/1");
        assertThat(parentCoordinates.getProperty("maven/parent/artifact/jar/1")).isEmpty();
        assertThat(parent.resolve(BuildStep.DEPENDENCIES)).exists().content().isEmpty();
        Path parentTests = results.get("maven/module/test-module-/declare");
        assertThat(parentTests.resolve(BuildStep.COORDINATES)).exists();
        Properties parentTestCoordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(parentTests.resolve(BuildStep.COORDINATES))) {
            parentTestCoordinates.load(reader);
        }
        assertThat(parentTestCoordinates).containsOnlyKeys(
                "maven/parent/artifact/jar/tests/1",
                "maven/parent/artifact/pom/1");
        assertThat(parentTestCoordinates.getProperty("maven/parent/artifact/jar/tests/1")).isEmpty();
        Properties parentTestDependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(parentTests.resolve(BuildStep.DEPENDENCIES))) {
            parentTestDependencies.load(reader);
        }
        assertThat(parentTestDependencies).containsOnlyKeys("maven/parent/artifact/jar/1");
        assertThat(parentTestDependencies.getProperty("maven/parent/artifact/jar/1")).isEmpty();
        Path child = results.get("maven/module/module-subproject/declare");
        assertThat(child.resolve(BuildStep.COORDINATES)).exists();
        Properties childCoordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(child.resolve(BuildStep.COORDINATES))) {
            childCoordinates.load(reader);
        }
        assertThat(childCoordinates).containsOnlyKeys(
                "maven/group/artifact/jar/1",
                "maven/group/artifact/pom/1");
        assertThat(childCoordinates.getProperty("maven/group/artifact/jar/1")).isEmpty();
        assertThat(child.resolve(BuildStep.DEPENDENCIES)).exists().content().isEmpty();
        Path childTests = results.get("maven/module/test-module-subproject/declare");
        assertThat(childTests.resolve(BuildStep.COORDINATES)).exists();
        Properties childTestCoordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(childTests.resolve(BuildStep.COORDINATES))) {
            childTestCoordinates.load(reader);
        }
        assertThat(childTestCoordinates).containsOnlyKeys(
                "maven/group/artifact/jar/tests/1",
                "maven/group/artifact/pom/1");
        assertThat(childTestCoordinates.getProperty("maven/group/artifact/jar/tests/1")).isEmpty();
        Properties childTestDependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(childTests.resolve(BuildStep.DEPENDENCIES))) {
            childTestDependencies.load(reader);
        }
        assertThat(childTestDependencies).containsOnlyKeys("maven/group/artifact/jar/1");
        assertThat(childTestDependencies.getProperty("maven/group/artifact/jar/1")).isEmpty();
    }

    @Test
    public void can_resolve_sources_and_resources() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("src/main/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("src/main/resources")).resolve("resource"), "bar");
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module/module-/declare",
                "maven/module/module-/sources",
                "maven/module/module-/resources-1");
        assertThat(results.get("maven/module/module-/sources").resolve(BuildStep.SOURCES + "source")).content().isEqualTo("foo");
        assertThat(results.get("maven/module/module-/resources-1").resolve(BuildStep.RESOURCES + "resource")).content().isEqualTo("bar");
    }

    @Test
    public void can_resolve_sources_and_resources_explicit() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <build>
                       <sourceDirectory>sources</sourceDirectory>
                       <resources>
                         <resource>resources-1</resource>
                         <resource>resources-2</resource>
                       </resources>
                    </build>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("sources")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("resources-1")).resolve("resource1"), "bar");
        Files.writeString(Files.createDirectories(project.resolve("resources-2")).resolve("resource2"), "qux");
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module/module-/declare",
                "maven/module/module-/sources",
                "maven/module/module-/resources-1",
                "maven/module/module-/resources-2");
        assertThat(results.get("maven/module/module-/sources").resolve(BuildStep.SOURCES + "source")).content().isEqualTo("foo");
        assertThat(results.get("maven/module/module-/resources-1").resolve(BuildStep.RESOURCES + "resource1")).content().isEqualTo("bar");
        assertThat(results.get("maven/module/module-/resources-2").resolve(BuildStep.RESOURCES + "resource2")).content().isEqualTo("qux");
    }

    @Test
    public void can_resolve_test_sources_and_resources() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("src/test/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("src/test/resources")).resolve("resource"), "bar");
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module/test-module-/declare",
                "maven/module/test-module-/sources",
                "maven/module/test-module-/resources-1");
        assertThat(results.get("maven/module/test-module-/sources").resolve(BuildStep.SOURCES + "source")).content().isEqualTo("foo");
        assertThat(results.get("maven/module/test-module-/resources-1").resolve(BuildStep.RESOURCES + "resource")).content().isEqualTo("bar");
    }

    @Test
    public void can_resolve_test_sources_and_resources_explicit() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <build>
                       <testSourceDirectory>sources</testSourceDirectory>
                       <testResources>
                         <testResource>resources-1</testResource>
                         <testResource>resources-2</testResource>
                       </testResources>
                    </build>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("sources")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("resources-1")).resolve("resource1"), "bar");
        Files.writeString(Files.createDirectories(project.resolve("resources-2")).resolve("resource2"), "qux");
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module/test-module-/declare",
                "maven/module/test-module-/sources",
                "maven/module/test-module-/resources-1",
                "maven/module/test-module-/resources-2");
        assertThat(results.get("maven/module/test-module-/sources").resolve(BuildStep.SOURCES + "source")).content().isEqualTo("foo");
        assertThat(results.get("maven/module/test-module-/resources-1").resolve(BuildStep.RESOURCES + "resource1")).content().isEqualTo("bar");
        assertThat(results.get("maven/module/test-module-/resources-2").resolve(BuildStep.RESOURCES + "resource2")).content().isEqualTo("qux");
    }

    @Test
    public void can_resolve_multi_module_project() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>parent</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>foo</module>
                        <module>bar</module>
                    </modules>
                </project>
                """);
        Files.writeString(Files.createDirectory(project.resolve("foo")).resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>group</groupId>
                        <artifactId>parent</artifactId>
                        <version>1</version>
                    </parent>
                    <artifactId>foo</artifactId>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("foo/src/main/java/foo")).resolve("Foo.java"), """
                package foo;
                public class Foo { }
                """);
        Files.writeString(Files.createDirectory(project.resolve("bar")).resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>group</groupId>
                        <artifactId>parent</artifactId>
                        <version>1</version>
                    </parent>
                    <artifactId>bar</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>group</groupId>
                            <artifactId>foo</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("bar/src/main/java/bar")).resolve("Bar.java"), """
                package bar;
                import foo.Foo;
                public class Bar extends Foo { }
                """);
        BuildExecutor root = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        root.addModule("maven", MavenProject.make(project,
                "maven",
                "SHA256",
                new MavenDefaultRepository(repository.toUri(), null, Map.of()),
                new MavenPomResolver(),
                (name, dependencies) -> {
                    switch (name) {
                        case "module-foo" -> assertThat(dependencies).isEmpty();
                        case "module-bar" -> assertThat(dependencies).containsExactly("module-foo");
                        default -> fail("Unexpected module: " + name);
                    }
                    return (buildExecutor, inherited) -> {
                        switch (name) {
                            case "module-foo" -> assertThat(inherited).containsOnlyKeys(
                                    "../../../../identify/module/module-foo/sources",
                                    "../../../../identify/module/module-foo/declare",
                                    "../dependencies/prepared",
                                    "../dependencies/resolved",
                                    "../dependencies/artifacts");
                            case "module-bar" -> assertThat(inherited).containsOnlyKeys(
                                    "../../../../identify/module/module-bar/sources",
                                    "../../../../identify/module/module-bar/declare",
                                    "../dependencies/prepared",
                                    "../dependencies/resolved",
                                    "../dependencies/artifacts",
                                    "../../module-foo/prepare",
                                    "../../module-foo/dependencies/prepared",
                                    "../../module-foo/dependencies/resolved",
                                    "../../module-foo/dependencies/artifacts",
                                    "../../module-foo/build/java/classes",
                                    "../../module-foo/build/java/artifacts",
                                    "../../module-foo/assign");
                            default -> fail("Unexpected module: " + name);
                        }
                        buildExecutor.addModule("java",
                                new JavaModule(),
                                Stream.concat(
                                                Stream.of("../dependencies/artifacts"),
                                                inherited.sequencedKeySet().stream().filter(identity -> identity.startsWith("../../../")))
                                        .collect(Collectors.toCollection(LinkedHashSet::new)));
                    };
                }));
        SequencedMap<String, Path> results = root.execute(Runnable::run).toCompletableFuture().join();
        Properties foo = new SequencedProperties();
        try (Reader reader = Files.newBufferedReader(results
                .get("maven/build/module/module-foo/assign")
                .resolve(BuildStep.COORDINATES))) {
            foo.load(reader);
        }
        assertThat(foo.stringPropertyNames()).containsExactly("maven/group/foo/jar/1", "maven/group/foo/pom/1");
        assertThat(foo.getProperty("maven/group/foo/jar/1")).isEqualTo(build
                .resolve("maven/build/module/module-foo/build/java/artifacts/output/artifacts/classes.jar")
                .toString());
        assertThat(foo.getProperty("maven/group/foo/pom/1")).isEqualTo(build
                .resolve("maven/identify/scan/output/pom/foo/pom.xml")
                .toString());
        Properties bar = new SequencedProperties();
        try (Reader reader = Files.newBufferedReader(results
                .get("maven/build/module/module-bar/assign")
                .resolve(BuildStep.COORDINATES))) {
            bar.load(reader);
        }
        assertThat(bar.stringPropertyNames()).containsExactly("maven/group/bar/jar/1", "maven/group/bar/pom/1");
        assertThat(bar.getProperty("maven/group/bar/jar/1")).isEqualTo(build
                .resolve("maven/build/module/module-bar/build/java/artifacts/output/artifacts/classes.jar")
                .toString());
        assertThat(bar.getProperty("maven/group/bar/pom/1")).isEqualTo(build
                .resolve("maven/identify/scan/output/pom/bar/pom.xml")
                .toString());
    }
}
