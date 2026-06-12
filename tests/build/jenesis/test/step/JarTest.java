package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.step.ProcessHandler;
import build.jenesis.step.Jar;
import build.jenesis.step.Javac;
import build.jenesis.step.Javadoc;
import sample.Sample;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JarTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, classes;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        classes = Files.createDirectory(root.resolve("classes"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void can_execute_classes_jar(boolean process) throws IOException {
        Path folder = Files.createDirectory(classes.resolve(Javac.CLASSES));
        try (InputStream inputStream = Sample.class.getResourceAsStream(Sample.class.getSimpleName() + ".class")) {
            Files.copy(requireNonNull(inputStream), Files
                    .createDirectory(folder.resolve("sample"))
                    .resolve("Sample.class"));
        }
        BuildStepResult result = new Jar(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL, Jar.Sort.CLASSES).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        classes,
                        Map.of(Path.of("sample/Sample.class"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(BuildStep.ARTIFACTS + "classes.jar")).isNotEmptyFile();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void can_execute_sources_jar(boolean process) throws IOException {
        Path folder = Files.createDirectory(classes.resolve(Javac.SOURCES));
        Files.writeString(Files
                .createDirectory(folder.resolve("sample"))
                .resolve("Sample.java"), """
                package sample;
                public class Sample {
                    public static void main(String[] args) {
                        System.out.print("Hello world!");
                    }
                }
                """);
        BuildStepResult result = new Jar(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL, Jar.Sort.SOURCES).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        classes,
                        Map.of(Path.of("sample/Sample.java"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(BuildStep.SOURCES + "sources.jar")).isNotEmptyFile();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void can_execute_javadoc_jar(boolean process) throws IOException {
        Path folder = Files.createDirectory(classes.resolve(Javadoc.JAVADOC));
        Files.writeString(Files
                .createDirectory(folder.resolve("sample"))
                .resolve("Sample.html"), """
                <html>
                  <p>This is a javadoc.</p>
                </html>
                """);
        BuildStepResult result = new Jar(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL, Jar.Sort.JAVADOC).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        classes,
                        Map.of(Path.of("sample/Sample.html"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(BuildStep.DOCUMENTATION + "javadoc.jar")).isNotEmptyFile();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void classes_jar_includes_multi_release_manifest_when_supplied(boolean process) throws IOException {
        Path folder = Files.createDirectory(classes.resolve(Javac.CLASSES));
        try (InputStream inputStream = Sample.class.getResourceAsStream(Sample.class.getSimpleName() + ".class")) {
            Files.copy(requireNonNull(inputStream), Files
                    .createDirectory(folder.resolve("sample"))
                    .resolve("Sample.class"));
        }
        Files.writeString(classes.resolve("manifest.mf"), "Manifest-Version: 1.0\r\nMulti-Release: true\r\n");
        BuildStepResult result = new Jar(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL, Jar.Sort.CLASSES).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        classes,
                        Map.of(Path.of("sample/Sample.class"), Checksum.ADDED,
                                Path.of("manifest.mf"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Path jar = next.resolve(BuildStep.ARTIFACTS + "classes.jar");
        assertThat(jar).isNotEmptyFile();
        assertThat(supplement.resolve("manifest.mf"))
                .as("merged manifest is staged into supplement, not the step output")
                .isRegularFile();
        assertThat(next.resolve("manifest.mf")).doesNotExist();
        try (JarInputStream jarStream = new JarInputStream(Files.newInputStream(jar))) {
            assertThat(jarStream.getManifest().getMainAttributes().getValue("Multi-Release"))
                    .isEqualTo("true");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void classes_jar_merges_manifests_from_multiple_predecessors(boolean process) throws IOException {
        Path firstFolder = Files.createDirectory(classes.resolve(Javac.CLASSES));
        try (InputStream inputStream = Sample.class.getResourceAsStream(Sample.class.getSimpleName() + ".class")) {
            Files.copy(requireNonNull(inputStream), Files
                    .createDirectory(firstFolder.resolve("sample"))
                    .resolve("Sample.class"));
        }
        Files.writeString(classes.resolve("manifest.mf"), "Manifest-Version: 1.0\r\nMulti-Release: true\r\n");
        Path second = Files.createDirectory(root.resolve("second"));
        Files.writeString(second.resolve("manifest.mf"),
                "Manifest-Version: 1.0\r\nMain-Class: sample.Sample\r\nImplementation-Title: example\r\n");
        BuildStepResult result = new Jar(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL, Jar.Sort.CLASSES).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of(
                        "classes", new BuildStepArgument(
                                classes,
                                Map.of(Path.of("sample/Sample.class"), Checksum.ADDED,
                                        Path.of("manifest.mf"), Checksum.ADDED)),
                        "second", new BuildStepArgument(
                                second,
                                Map.of(Path.of("manifest.mf"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        try (JarInputStream jarStream = new JarInputStream(Files.newInputStream(
                next.resolve(BuildStep.ARTIFACTS + "classes.jar")))) {
            java.util.jar.Attributes attributes = jarStream.getManifest().getMainAttributes();
            assertThat(attributes.getValue("Multi-Release")).isEqualTo("true");
            assertThat(attributes.getValue("Main-Class")).isEqualTo("sample.Sample");
            assertThat(attributes.getValue("Implementation-Title")).isEqualTo("example");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void identical_manifest_values_across_predecessors_do_not_conflict(boolean process) throws IOException {
        Path firstFolder = Files.createDirectory(classes.resolve(Javac.CLASSES));
        try (InputStream inputStream = Sample.class.getResourceAsStream(Sample.class.getSimpleName() + ".class")) {
            Files.copy(requireNonNull(inputStream), Files
                    .createDirectory(firstFolder.resolve("sample"))
                    .resolve("Sample.class"));
        }
        Files.writeString(classes.resolve("manifest.mf"), "Manifest-Version: 1.0\r\nMulti-Release: true\r\n");
        Path second = Files.createDirectory(root.resolve("second"));
        Files.writeString(second.resolve("manifest.mf"), "Manifest-Version: 1.0\r\nMulti-Release: true\r\n");
        BuildStepResult result = new Jar(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL, Jar.Sort.CLASSES).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of(
                        "classes", new BuildStepArgument(
                                classes,
                                Map.of(Path.of("sample/Sample.class"), Checksum.ADDED,
                                        Path.of("manifest.mf"), Checksum.ADDED)),
                        "second", new BuildStepArgument(
                                second,
                                Map.of(Path.of("manifest.mf"), Checksum.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
    }

    @Test
    public void conflicting_manifest_attributes_across_predecessors_throw() throws IOException {
        Files.writeString(classes.resolve("manifest.mf"), "Manifest-Version: 1.0\r\nMain-Class: a.A\r\n");
        Path second = Files.createDirectory(root.resolve("second"));
        Files.writeString(second.resolve("manifest.mf"), "Manifest-Version: 1.0\r\nMain-Class: b.B\r\n");
        LinkedHashMap<String, BuildStepArgument> args = new LinkedHashMap<>();
        args.put("classes", new BuildStepArgument(classes, Map.of(Path.of("manifest.mf"), Checksum.ADDED)));
        args.put("second", new BuildStepArgument(second, Map.of(Path.of("manifest.mf"), Checksum.ADDED)));
        assertThatThrownBy(() -> new Jar(ProcessHandler.Factory.TOOL, Jar.Sort.CLASSES).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                args).toCompletableFuture().join())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Conflicting manifest attribute 'Main-Class' in "
                        + second.resolve("manifest.mf")
                        + ": 'a.A' vs 'b.B'");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void produces_deterministic_output(boolean process) throws IOException {
        Path folder = Files.createDirectory(classes.resolve(Javac.CLASSES));
        try (InputStream inputStream = Sample.class.getResourceAsStream(Sample.class.getSimpleName() + ".class")) {
            Files.copy(requireNonNull(inputStream), Files
                    .createDirectory(folder.resolve("sample"))
                    .resolve("Sample.class"));
        }
        Path firstNext = Files.createDirectory(root.resolve("first"));
        Path secondNext = Files.createDirectory(root.resolve("second"));
        BuildStepArgument argument = new BuildStepArgument(
                classes,
                Map.of(Path.of("sample/Sample.class"), Checksum.ADDED));
        Jar jar = new Jar(process ? ProcessHandler.Factory.FORK : ProcessHandler.Factory.TOOL, Jar.Sort.CLASSES);
        jar.apply(Runnable::run,
                new BuildStepContext(previous, firstNext, supplement),
                new LinkedHashMap<>(Map.of("sources", argument))).toCompletableFuture().join();
        jar.apply(Runnable::run,
                new BuildStepContext(previous, secondNext, supplement),
                new LinkedHashMap<>(Map.of("sources", argument))).toCompletableFuture().join();
        assertThat(Files.readAllBytes(firstNext.resolve(BuildStep.ARTIFACTS + "classes.jar")))
                .isEqualTo(Files.readAllBytes(secondNext.resolve(BuildStep.ARTIFACTS + "classes.jar")));
    }
}
