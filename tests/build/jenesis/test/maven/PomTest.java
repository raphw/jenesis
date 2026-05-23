package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.Pom;
import build.jenesis.project.DependencyScope;

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
        SequencedProperties coordinates = new SequencedProperties();
        coordinates.setProperty("maven/build.jenesis/jenesis/jar/1.0.0", "");
        coordinates.setProperty("maven/build.jenesis/jenesis/pom/1.0.0", "/somewhere/pom.xml");
        coordinates.store(argument.resolve(BuildStep.IDENTITY));
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("maven/org.example/lib/1.2.3", "");
        dependencies.setProperty("maven/org.example/other/jar/4.5.6", "");
        dependencies.setProperty("maven/org.example/zip/zip/7.8.9", "");
        dependencies.setProperty("module/com.example.foo", "");
        dependencies.store(argument.resolve(BuildStep.REQUIRES));
        SequencedProperties metadata = new SequencedProperties();
        metadata.setProperty("project", "build.jenesis");
        metadata.setProperty("artifact", "jenesis");
        metadata.setProperty("version", "1.0.0");
        metadata.store(argument.resolve(BuildStep.METADATA));
        BuildStepResult result = new Pom().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(
                                        Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.METADATA), ChecksumStatus.ADDED)))))
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
    public void compile_only_dependency_is_emitted_with_provided_scope() throws IOException {
        SequencedProperties coordinates = new SequencedProperties();
        coordinates.setProperty("maven/build.jenesis/jenesis/jar/1.0.0", "");
        coordinates.store(argument.resolve(BuildStep.IDENTITY));
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("maven/org.example/lib/1.2.3", "");
        requires.setProperty("maven/org.example/static-lib/4.5.6", "");
        requires.store(argument.resolve(BuildStep.REQUIRES));
        SequencedProperties scopes = new SequencedProperties();
        scopes.setProperty("maven/org.example/lib/1.2.3", DependencyScope.COMPILE.name() + "," + DependencyScope.RUNTIME.name());
        scopes.setProperty("maven/org.example/static-lib/4.5.6", DependencyScope.COMPILE.name());
        scopes.store(argument.resolve(BuildStep.SCOPES));
        SequencedProperties metadata = new SequencedProperties();
        metadata.setProperty("project", "build.jenesis");
        metadata.setProperty("artifact", "jenesis");
        metadata.setProperty("version", "1.0.0");
        metadata.store(argument.resolve(BuildStep.METADATA));
        BuildStepResult result = new Pom().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.SCOPES), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.METADATA), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        String pom = Files.readString(next.resolve(Pom.POM));
        assertThat(pom).contains("<artifactId>lib</artifactId>");
        assertThat(pom).contains("<artifactId>static-lib</artifactId>");
        long providedScopes = pom.lines().filter(line -> line.trim().equals("<scope>provided</scope>")).count();
        assertThat(providedScopes).isEqualTo(1);
        int libIndex = pom.indexOf("<artifactId>lib</artifactId>");
        int staticLibIndex = pom.indexOf("<artifactId>static-lib</artifactId>");
        int providedIndex = pom.indexOf("<scope>provided</scope>");
        assertThat(providedIndex).isGreaterThan(staticLibIndex);
        assertThat(libIndex).isLessThan(staticLibIndex);
    }

    @Test
    public void runtime_only_dependency_is_emitted_with_runtime_scope() throws IOException {
        SequencedProperties coordinates = new SequencedProperties();
        coordinates.setProperty("maven/build.jenesis/jenesis/jar/1.0.0", "");
        coordinates.store(argument.resolve(BuildStep.IDENTITY));
        SequencedProperties requires = new SequencedProperties();
        requires.setProperty("maven/org.example/lib/1.2.3", "");
        requires.setProperty("maven/org.example/runtime-only/4.5.6", "");
        requires.store(argument.resolve(BuildStep.REQUIRES));
        SequencedProperties scopes = new SequencedProperties();
        scopes.setProperty("maven/org.example/lib/1.2.3", DependencyScope.COMPILE.name() + "," + DependencyScope.RUNTIME.name());
        scopes.setProperty("maven/org.example/runtime-only/4.5.6", DependencyScope.RUNTIME.name());
        scopes.store(argument.resolve(BuildStep.SCOPES));
        SequencedProperties metadata = new SequencedProperties();
        metadata.setProperty("project", "build.jenesis");
        metadata.setProperty("artifact", "jenesis");
        metadata.setProperty("version", "1.0.0");
        metadata.store(argument.resolve(BuildStep.METADATA));
        new Pom().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.SCOPES), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.METADATA), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        String pom = Files.readString(next.resolve(Pom.POM));
        assertThat(pom).contains("<artifactId>lib</artifactId>");
        assertThat(pom).contains("<artifactId>runtime-only</artifactId>");
        long runtimeScopes = pom.lines().filter(line -> line.trim().equals("<scope>runtime</scope>")).count();
        assertThat(runtimeScopes).isEqualTo(1);
        int libIndex = pom.indexOf("<artifactId>lib</artifactId>");
        int runtimeOnlyIndex = pom.indexOf("<artifactId>runtime-only</artifactId>");
        int runtimeScopeIndex = pom.indexOf("<scope>runtime</scope>");
        assertThat(runtimeScopeIndex).isGreaterThan(runtimeOnlyIndex);
        assertThat(libIndex).isLessThan(runtimeOnlyIndex);
    }

    @Test
    public void metadata_version_is_emitted_in_pom() throws IOException {
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("maven/org.example/lib/1.2.3", "");
        dependencies.store(argument.resolve(BuildStep.REQUIRES));
        SequencedProperties metadata = new SequencedProperties();
        metadata.setProperty("project", "build.jenesis");
        metadata.setProperty("artifact", "jenesis");
        metadata.setProperty("version", "2.7.1");
        metadata.store(argument.resolve(BuildStep.METADATA));
        BuildStepResult result = new Pom().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(
                                        Path.of(BuildStep.REQUIRES), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.METADATA), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        String pom = Files.readString(next.resolve(Pom.POM));
        assertThat(pom).contains("<version>2.7.1</version>");
        assertThat(pom).contains("<version>1.2.3</version>");
    }

    @Test
    public void missing_metadata_version_throws() throws IOException {
        SequencedProperties metadata = new SequencedProperties();
        metadata.setProperty("project", "build.jenesis");
        metadata.setProperty("artifact", "jenesis");
        metadata.store(argument.resolve(BuildStep.METADATA));
        assertThatThrownBy(() -> new Pom().apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                        argument,
                        Map.of(Path.of(BuildStep.METADATA), ChecksumStatus.ADDED))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing 'version'");
    }

    @Test
    public void metadata_properties_populate_emitted_pom() throws IOException {
        SequencedProperties coordinates = new SequencedProperties();
        coordinates.setProperty("maven/build.jenesis/jenesis/jar/1.0.0", "");
        coordinates.store(argument.resolve(BuildStep.IDENTITY));
        SequencedProperties metadata = new SequencedProperties();
        metadata.setProperty("project", "build.jenesis");
        metadata.setProperty("artifact", "jenesis");
        metadata.setProperty("version", "1.0.0");
        metadata.setProperty("name", "Jenesis");
        metadata.setProperty("description", "A build tool.");
        metadata.setProperty("url", "https://example.com/jenesis");
        metadata.setProperty("license.apache-2_0.name", "Apache-2.0");
        metadata.setProperty("license.apache-2_0.url", "https://www.apache.org/licenses/LICENSE-2.0.txt");
        metadata.setProperty("developer.alice.name", "Alice Example");
        metadata.setProperty("developer.alice.email", "alice@example.com");
        metadata.setProperty("developer.bob.name", "Bob Example");
        metadata.setProperty("developer.bob.email", "bob@example.com");
        metadata.setProperty("scm.connection", "scm:git:https://example.com/jenesis.git");
        metadata.setProperty("scm.url", "https://example.com/jenesis");
        metadata.store(argument.resolve(BuildStep.METADATA));
        BuildStepResult result = new Pom().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(
                                        Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.METADATA), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        String pom = Files.readString(next.resolve(Pom.POM));
        assertThat(pom).contains("<name>Jenesis</name>");
        assertThat(pom).contains("<description>A build tool.</description>");
        assertThat(pom).contains("<url>https://example.com/jenesis</url>");
        assertThat(pom).contains("<name>Apache-2.0</name>");
        assertThat(pom).contains("<id>alice</id>");
        assertThat(pom).contains("<email>alice@example.com</email>");
        assertThat(pom).contains("<id>bob</id>");
        assertThat(pom).contains("<email>bob@example.com</email>");
        assertThat(pom).contains("<connection>scm:git:https://example.com/jenesis.git</connection>");
    }

    @Test
    public void user_metadata_overrides_pom_derived_metadata_when_both_are_provided() throws IOException {
        SequencedProperties coordinates = new SequencedProperties();
        coordinates.setProperty("maven/com.example/foo/jar/1.0.0", "");
        coordinates.store(argument.resolve(BuildStep.IDENTITY));
        SequencedProperties pomDerived = new SequencedProperties();
        pomDerived.setProperty("project", "com.example");
        pomDerived.setProperty("artifact", "foo");
        pomDerived.setProperty("version", "1.0.0");
        pomDerived.setProperty("url", "https://example.com/pom-url");
        pomDerived.store(argument.resolve(BuildStep.METADATA));
        Path userMetadata = Files.createDirectory(root.resolve("user-metadata"));
        SequencedProperties user = new SequencedProperties();
        user.setProperty("version", "2.7.1");
        user.setProperty("url", "https://example.com/user-url");
        user.store(userMetadata.resolve(BuildStep.METADATA));
        LinkedHashMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        arguments.put("pom", new BuildStepArgument(argument, Map.of(
                Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                Path.of(BuildStep.METADATA), ChecksumStatus.ADDED)));
        arguments.put("user", new BuildStepArgument(userMetadata, Map.of(
                Path.of(BuildStep.METADATA), ChecksumStatus.ADDED)));
        BuildStepResult result = new Pom().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        arguments)
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        String pom = Files.readString(next.resolve(Pom.POM));
        assertThat(pom).contains("<groupId>com.example</groupId>");
        assertThat(pom).contains("<artifactId>foo</artifactId>");
        assertThat(pom).contains("<version>2.7.1</version>");
        assertThat(pom).contains("<url>https://example.com/user-url</url>");
        assertThat(pom).doesNotContain("<version>1.0.0</version>");
        assertThat(pom).doesNotContain("https://example.com/pom-url");
    }

    @Test
    public void pom_derived_metadata_supplies_fields_user_did_not_override() throws IOException {
        SequencedProperties coordinates = new SequencedProperties();
        coordinates.setProperty("maven/com.example/foo/jar/0-SNAPSHOT", "");
        coordinates.store(argument.resolve(BuildStep.IDENTITY));
        SequencedProperties pomDerived = new SequencedProperties();
        pomDerived.setProperty("project", "com.example");
        pomDerived.setProperty("artifact", "foo");
        pomDerived.setProperty("version", "1.0.0");
        pomDerived.setProperty("url", "https://example.com/pom-url");
        pomDerived.store(argument.resolve(BuildStep.METADATA));
        Path userMetadata = Files.createDirectory(root.resolve("user-metadata"));
        SequencedProperties user = new SequencedProperties();
        user.setProperty("license.apache-2_0.name", "Apache-2.0");
        user.store(userMetadata.resolve(BuildStep.METADATA));
        LinkedHashMap<String, BuildStepArgument> arguments = new LinkedHashMap<>();
        arguments.put("pom", new BuildStepArgument(argument, Map.of(
                Path.of(BuildStep.IDENTITY), ChecksumStatus.ADDED,
                Path.of(BuildStep.METADATA), ChecksumStatus.ADDED)));
        arguments.put("user", new BuildStepArgument(userMetadata, Map.of(
                Path.of(BuildStep.METADATA), ChecksumStatus.ADDED)));
        new Pom().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        arguments)
                .toCompletableFuture()
                .join();
        String pom = Files.readString(next.resolve(Pom.POM));
        assertThat(pom).contains("<groupId>com.example</groupId>");
        assertThat(pom).contains("<artifactId>foo</artifactId>");
        assertThat(pom).contains("<version>1.0.0</version>");
        assertThat(pom).contains("<url>https://example.com/pom-url</url>");
        assertThat(pom).contains("<name>Apache-2.0</name>");
    }

}
