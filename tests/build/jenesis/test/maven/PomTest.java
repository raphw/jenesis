package build.jenesis.test.maven;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.Pom;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PomTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, argument;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        argument = Files.createDirectory(root.resolve("argument"));
    }

    @Test
    public void can_emit_pom_from_files() throws IOException {
        Properties coordinates = new SequencedProperties();
        coordinates.setProperty("maven/build.jenesis/jenesis/jar/1.0.0", "");
        coordinates.setProperty("maven/build.jenesis/jenesis/pom/1.0.0", "/somewhere/pom.xml");
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.COORDINATES))) {
            coordinates.store(writer, null);
        }
        Properties dependencies = new SequencedProperties();
        dependencies.setProperty("maven/org.example/lib/1.2.3", "");
        dependencies.setProperty("maven/org.example/other/jar/4.5.6", "");
        dependencies.setProperty("maven/org.example/zip/zip/7.8.9", "");
        dependencies.setProperty("module/com.example.foo", "");
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.DEPENDENCIES))) {
            dependencies.store(writer, null);
        }
        BuildStepResult result = new Pom().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(
                                        Path.of(BuildStep.COORDINATES), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.DEPENDENCIES), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        assertThat(Files.readString(next.resolve(Pom.POM))).isEqualTo("""
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <project xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>build.jenesis</groupId>
                    <artifactId>jenesis</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>lib</artifactId>
                            <version>1.2.3</version>
                        </dependency>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>other</artifactId>
                            <version>4.5.6</version>
                        </dependency>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>zip</artifactId>
                            <version>7.8.9</version>
                            <type>zip</type>
                        </dependency>
                    </dependencies>
                </project>
                """);
    }

    @Test
    public void fails_when_no_self_coordinate_is_present() throws IOException {
        Properties coordinates = new SequencedProperties();
        coordinates.setProperty("maven/build.jenesis/jenesis/jar/1.0.0", "/already/resolved.jar");
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.COORDINATES))) {
            coordinates.store(writer, null);
        }
        assertThatThrownBy(() -> new Pom().apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                        argument,
                        Map.of(Path.of(BuildStep.COORDINATES), ChecksumStatus.ADDED))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No own Maven coordinate");
    }
}
