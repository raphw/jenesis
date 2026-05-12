package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenRepositoryLayout;
import build.jenesis.maven.Pom;

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
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.IDENTITY))) {
            coordinates.store(writer, null);
        }
        Properties dependencies = new SequencedProperties();
        dependencies.setProperty("maven/org.example/lib/1.2.3", "");
        dependencies.setProperty("maven/org.example/other/jar/4.5.6", "");
        dependencies.setProperty("maven/org.example/zip/zip/7.8.9", "");
        dependencies.setProperty("module/com.example.foo", "");
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.REQUIRES))) {
            dependencies.store(writer, null);
        }
        BuildStepResult result = new Pom().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(
                                        Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED)))))
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
    public void default_resolver_translates_module_self_coordinate_to_maven() throws IOException {
        Properties coordinates = new SequencedProperties();
        coordinates.setProperty("module/build.jenesis.test", "");
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.IDENTITY))) {
            coordinates.store(writer, null);
        }
        Properties dependencies = new SequencedProperties();
        dependencies.setProperty("maven/build.jenesis/jenesis/0-SNAPSHOT", "");
        dependencies.setProperty("module/some.other.module", "");
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.REQUIRES))) {
            dependencies.store(writer, null);
        }
        BuildStepResult result = new Pom().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(
                                        Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        assertThat(Files.readString(next.resolve(Pom.POM))).isEqualTo("""
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <project xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>build.jenesis</groupId>
                    <artifactId>build.jenesis.test</artifactId>
                    <version>0-SNAPSHOT</version>
                    <dependencies>
                        <dependency>
                            <groupId>build.jenesis</groupId>
                            <artifactId>jenesis</artifactId>
                            <version>0-SNAPSHOT</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
    }

    @Test
    public void jenesis_buildVersion_overrides_self_version_in_emitted_pom() throws IOException {
        Properties coordinates = new SequencedProperties();
        coordinates.setProperty("maven/build.jenesis/jenesis/jar/0-SNAPSHOT", "");
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.IDENTITY))) {
            coordinates.store(writer, null);
        }
        Properties dependencies = new SequencedProperties();
        dependencies.setProperty("maven/org.example/lib/1.2.3", "");
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.REQUIRES))) {
            dependencies.store(writer, null);
        }
        String previousProperty = System.getProperty("jenesis.buildVersion");
        System.setProperty("jenesis.buildVersion", "2.7.1");
        try {
            BuildStepResult result = new Pom().apply(Runnable::run,
                            new BuildStepContext(previous, next, supplement),
                            new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                    argument,
                                    Map.of(
                                            Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                                            Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED)))))
                    .toCompletableFuture()
                    .join();
            assertThat(result.next()).isTrue();
            String pom = Files.readString(next.resolve(Pom.POM));
            assertThat(pom).contains("<version>2.7.1</version>");
            assertThat(pom).contains("<version>1.2.3</version>");
        } finally {
            if (previousProperty == null) {
                System.clearProperty("jenesis.buildVersion");
            } else {
                System.setProperty("jenesis.buildVersion", previousProperty);
            }
        }
    }

    @Test
    public void jenesis_buildVersion_overrides_default_resolver_snapshot_version() throws IOException {
        Properties coordinates = new SequencedProperties();
        coordinates.setProperty("module/build.jenesis.test", "");
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.IDENTITY))) {
            coordinates.store(writer, null);
        }
        String previousProperty = System.getProperty("jenesis.buildVersion");
        System.setProperty("jenesis.buildVersion", "9.0.0");
        try {
            BuildStepResult result = new Pom().apply(Runnable::run,
                            new BuildStepContext(previous, next, supplement),
                            new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                    argument,
                                    Map.of(Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED)))))
                    .toCompletableFuture()
                    .join();
            assertThat(result.next()).isTrue();
            assertThat(Files.readString(next.resolve(Pom.POM))).contains("<version>9.0.0</version>");
        } finally {
            if (previousProperty == null) {
                System.clearProperty("jenesis.buildVersion");
            } else {
                System.setProperty("jenesis.buildVersion", previousProperty);
            }
        }
    }

    @Test
    public void empty_jenesis_buildVersion_falls_back_to_self_version() throws IOException {
        Properties coordinates = new SequencedProperties();
        coordinates.setProperty("maven/build.jenesis/jenesis/jar/1.0.0", "");
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.IDENTITY))) {
            coordinates.store(writer, null);
        }
        String previousProperty = System.getProperty("jenesis.buildVersion");
        System.setProperty("jenesis.buildVersion", "");
        try {
            BuildStepResult result = new Pom().apply(Runnable::run,
                            new BuildStepContext(previous, next, supplement),
                            new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                    argument,
                                    Map.of(Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED)))))
                    .toCompletableFuture()
                    .join();
            assertThat(result.next()).isTrue();
            assertThat(Files.readString(next.resolve(Pom.POM))).contains("<version>1.0.0</version>");
        } finally {
            if (previousProperty == null) {
                System.clearProperty("jenesis.buildVersion");
            } else {
                System.setProperty("jenesis.buildVersion", previousProperty);
            }
        }
    }

    @Test
    public void jenesis_buildVersion_propagates_through_export_layout() throws IOException {
        Properties coordinates = new SequencedProperties();
        coordinates.setProperty("maven/com.example/foo/jar/0-SNAPSHOT", "");
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.IDENTITY))) {
            coordinates.store(writer, null);
        }
        Path exported = Files.createDirectory(root.resolve("repository"));
        String previousProperty = System.getProperty("jenesis.buildVersion");
        System.setProperty("jenesis.buildVersion", "2.7.1");
        try {
            new Pom().apply(Runnable::run,
                            new BuildStepContext(previous, next, supplement),
                            new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                    argument,
                                    Map.of(Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED)))))
                    .toCompletableFuture()
                    .join();
            Files.writeString(next.resolve("classes.jar"), "jar bytes");
            MavenRepositoryLayout.toRepository(exported).apply(Runnable::run,
                            new BuildStepContext(previous, Files.createDirectory(root.resolve("next2")), supplement),
                            new LinkedHashMap<>(Map.of("pom-and-jar", new BuildStepArgument(
                                    next,
                                    Map.of(Path.of("pom.xml"), ChecksumStatus.ADDED,
                                            Path.of("classes.jar"), ChecksumStatus.ADDED)))))
                    .toCompletableFuture()
                    .join();
            assertThat(exported.resolve("com/example/foo/2.7.1/foo-2.7.1.jar")).hasContent("jar bytes");
            assertThat(exported.resolve("com/example/foo/2.7.1/foo-2.7.1.pom")).exists();
        } finally {
            if (previousProperty == null) {
                System.clearProperty("jenesis.buildVersion");
            } else {
                System.setProperty("jenesis.buildVersion", previousProperty);
            }
        }
    }

    @Test
    public void fails_when_no_self_coordinate_is_present() throws IOException {
        Properties coordinates = new SequencedProperties();
        coordinates.setProperty("maven/build.jenesis/jenesis/jar/1.0.0", "/already/resolved.jar");
        try (Writer writer = Files.newBufferedWriter(argument.resolve(BuildStep.IDENTITY))) {
            coordinates.store(writer, null);
        }
        assertThatThrownBy(() -> new Pom().apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                        argument,
                        Map.of(Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No own Maven coordinate");
    }
}
