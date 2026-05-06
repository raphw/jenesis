package build.jenesis.test.step;

import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.step.Export;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;

public class ExportTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, source, target;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        source = Files.createDirectory(root.resolve("source"));
        target = Files.createDirectory(root.resolve("target"));
    }

    @Test
    public void copies_files_through_placement() throws IOException {
        Files.writeString(source.resolve("kept.jar"), "kept");
        Files.writeString(source.resolve("ignored.txt"), "ignored");

        Export export = new Export(target, (Function<Path, Optional<Path>> & Serializable) (file -> {
            String name = file.getFileName().toString();
            return name.endsWith(".jar")
                    ? Optional.of(Path.of("staged", name))
                    : Optional.empty();
        }));
        BuildStepResult result = export.apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("kept.jar"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        assertThat(target.resolve("staged/kept.jar")).hasContent("kept");
        assertThat(target.resolve("staged/ignored.txt")).doesNotExist();
    }

    @Test
    public void replaces_existing_files() throws IOException {
        Files.writeString(source.resolve("artifact.jar"), "new");
        Files.writeString(target.resolve("artifact.jar"), "stale");

        Export export = new Export(target,
                (Function<Path, Optional<Path>> & Serializable) (file -> Optional.of(Path.of(file.getFileName().toString()))));
        export.apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("artifact.jar"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(target.resolve("artifact.jar")).hasContent("new");
    }

    @Test
    public void should_run_returns_true() {
        Export export = new Export(target,
                (Function<Path, Optional<Path>> & Serializable) (_ -> Optional.empty()));
        assertThat(export.shouldRun(new LinkedHashMap<>())).isTrue();
    }

    @Test
    public void maven_layout_routes_jar_and_pom_under_groupId_artifactId_version() throws IOException {
        Path module = Files.createDirectory(source.resolve("module-x"));
        Files.writeString(module.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>foo</artifactId>
                    <version>1.2.3</version>
                </project>
                """);
        Files.writeString(module.resolve("classes.jar"), "jar bytes");

        Export export = Export.toMavenRepository(target);
        BuildStepResult result = export.apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("module-x/classes.jar"), ChecksumStatus.ADDED,
                                        Path.of("module-x/pom.xml"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();
        assertThat(target.resolve("com/example/foo/1.2.3/foo-1.2.3.jar")).hasContent("jar bytes");
        assertThat(target.resolve("com/example/foo/1.2.3/foo-1.2.3.pom")).exists();
    }

    @Test
    public void maven_layout_skips_files_without_sibling_pom() throws IOException {
        Files.writeString(source.resolve("classes.jar"), "ignored");

        Export export = Export.toMavenRepository(target);
        export.apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("source", new BuildStepArgument(
                                source,
                                Map.of(Path.of("classes.jar"), ChecksumStatus.ADDED)))))
                .toCompletableFuture()
                .join();
        try (Stream<Path> contents = Files.list(target)) {
            assertThat(contents).isEmpty();
        }
    }
}
