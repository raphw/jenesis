package build.buildbuddy.test.maven;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.BuildStep;
import build.buildbuddy.HashDigestFunction;
import build.buildbuddy.maven.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.SequencedMap;

import static org.assertj.core.api.Assertions.assertThat;

public class MavenProjectTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path repository, project, build;

    private MavenPomResolver mavenPomResolver;

    @Before
    public void setUp() throws Exception {
        repository = temporaryFolder.newFolder("repository").toPath();
        project = temporaryFolder.newFolder("project").toPath();
        build = temporaryFolder.newFolder("build").toPath();
        mavenPomResolver = new MavenPomResolver(
                new MavenDefaultRepository(repository.toUri(), null, Map.of()),
                MavenDefaultVersionNegotiator.maven(new MavenDefaultRepository(repository.toUri(), null, Map.of())));
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
        BuildExecutor executor = BuildExecutor.of(build, new HashDigestFunction("MD5"));
        executor.add("maven", new MavenProject("maven", project, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/define/module-/declare");
        Path folder = results.get("maven/define/module-/declare");
        assertThat(folder.resolve(BuildStep.COORDINATES)).exists();
        Properties coordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(folder.resolve(BuildStep.COORDINATES))) {
            coordinates.load(reader);
        }
        assertThat(coordinates).containsOnlyKeys("maven/group/artifact/jar/1");
        assertThat(coordinates.getProperty("maven/group/artifact/jar/1")).isEmpty();
        assertThat(folder.resolve(BuildStep.DEPENDENCIES)).exists();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(folder.resolve(BuildStep.DEPENDENCIES))) {
            dependencies.load(reader);
        }
        assertThat(dependencies).containsOnlyKeys("maven/other/artifact/jar/1");
        assertThat(dependencies.getProperty("maven/other/artifact/jar/1")).isEmpty();
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
        BuildExecutor executor = BuildExecutor.of(build, new HashDigestFunction("MD5"));
        executor.add("maven", new MavenProject("maven", project, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/define/module-/declare", "maven/define/module-subproject/declare");
        Path parent = results.get("maven/define/module-/declare");
        assertThat(parent.resolve(BuildStep.COORDINATES)).exists();
        Properties parentCoordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(parent.resolve(BuildStep.COORDINATES))) {
            parentCoordinates.load(reader);
        }
        assertThat(parentCoordinates).containsOnlyKeys("maven/parent/artifact/jar/1");
        assertThat(parentCoordinates.getProperty("maven/parent/artifact/jar/1")).isEmpty();
        assertThat(parent.resolve(BuildStep.DEPENDENCIES)).exists().content().isEmpty();
        Path child = results.get("maven/define/module-/declare");
        assertThat(child.resolve(BuildStep.COORDINATES)).exists();
        Properties childCoordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(child.resolve(BuildStep.COORDINATES))) {
            childCoordinates.load(reader);
        }
        assertThat(childCoordinates).containsOnlyKeys("maven/parent/artifact/jar/1");
        assertThat(childCoordinates.getProperty("maven/parent/artifact/jar/1")).isEmpty();
        assertThat(child.resolve(BuildStep.DEPENDENCIES)).exists().content().isEmpty();
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
        BuildExecutor executor = BuildExecutor.of(build, new HashDigestFunction("MD5"));
        executor.add("maven", new MavenProject("maven", project, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/define/module-/declare",
                "maven/define/module-/sources",
                "maven/define/module-/resources-1");
        assertThat(results.get("maven/define/module-/sources").resolve(BuildStep.SOURCES + "source")).content().isEqualTo("foo");
        assertThat(results.get("maven/define/module-/resources-1").resolve(BuildStep.RESOURCES + "resource")).content().isEqualTo("bar");
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
        BuildExecutor executor = BuildExecutor.of(build, new HashDigestFunction("MD5"));
        executor.add("maven", new MavenProject("maven", project, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/define/module-/declare",
                "maven/define/module-/sources",
                "maven/define/module-/resources-1",
                "maven/define/module-/resources-2");
        assertThat(results.get("maven/define/module-/sources").resolve(BuildStep.SOURCES + "source")).content().isEqualTo("foo");
        assertThat(results.get("maven/define/module-/resources-1").resolve(BuildStep.RESOURCES + "resource1")).content().isEqualTo("bar");
        assertThat(results.get("maven/define/module-/resources-2").resolve(BuildStep.RESOURCES + "resource2")).content().isEqualTo("qux");
    }
}
