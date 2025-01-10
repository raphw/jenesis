package build.buildbuddy.test.maven;

import build.buildbuddy.BuildExecutor;
import build.buildbuddy.HashDigestFunction;
import build.buildbuddy.maven.*;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
    public void can_resolve_dependencies() throws IOException {
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
        BuildExecutor executor = BuildExecutor.of(build, new HashDigestFunction("MD5"));
        executor.add("maven", new MavenProject("maven", project, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        System.out.println(results);
    }
}
