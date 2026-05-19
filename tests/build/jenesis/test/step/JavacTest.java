package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.step.Javac;

import static org.assertj.core.api.Assertions.assertThat;

public class JavacTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, sources;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        sources = Files.createDirectory(root.resolve("sources"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void can_execute_javac(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("Sample.java"))) {
            writer.append("package sample;");
            writer.newLine();
            writer.append("public class Sample { }");
            writer.newLine();
        }
        BuildStepResult result = (process ? Javac.process() : Javac.tool()).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/sample/Sample.java"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "sample/Sample.class")).isNotEmptyFile();
    }

    @Test
    public void writeRelease_writes_process_javac_properties_with_release_flag() throws IOException {
        Path folder = Files.createDirectory(root.resolve("write-release"));
        Javac.writeRelease(folder, "21");
        Path file = folder.resolve("process/javac.properties");
        assertThat(file).exists();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        assertThat(properties).containsEntry("--release", "21");
    }

    @Test
    public void writeRelease_null_or_empty_writes_nothing() throws IOException {
        Path folder = Files.createDirectory(root.resolve("write-release-empty"));
        Javac.writeRelease(folder, null);
        Javac.writeRelease(folder, "");
        assertThat(folder.resolve("process")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void release_in_process_javac_properties_is_forwarded_to_javac(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("Sample.java"))) {
            writer.append("package sample;");
            writer.newLine();
            writer.append("public class Sample { }");
            writer.newLine();
        }
        Javac.writeRelease(sources, "21");
        BuildStepResult result = (process ? Javac.process() : Javac.tool()).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/sample/Sample.java"), ChecksumStatus.ADDED,
                                Path.of("process/javac.properties"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "sample/Sample.class")).isNotEmptyFile();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void stamps_module_version_when_buildVersion_is_set(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("module-info.java"))) {
            writer.append("module sample { }");
            writer.newLine();
        }
        BuildStepResult result = (process ? Javac.process() : Javac.tool()).buildVersion("1.2.3").apply(Runnable::run,
                new BuildStepContext(this.previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/module-info.java"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Path moduleInfo = next.resolve(Javac.CLASSES + "module-info.class");
        assertThat(moduleInfo).isNotEmptyFile();
        ModuleDescriptor descriptor = ModuleDescriptor.read(Files.newInputStream(moduleInfo));
        assertThat(descriptor.rawVersion()).contains("1.2.3");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void does_not_stamp_module_version_when_buildVersion_is_absent(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("module-info.java"))) {
            writer.append("module sample { }");
            writer.newLine();
        }
        BuildStepResult result = (process ? Javac.process() : Javac.tool()).buildVersion(null).apply(Runnable::run,
                new BuildStepContext(this.previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/module-info.java"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        Path moduleInfo = next.resolve(Javac.CLASSES + "module-info.class");
        assertThat(moduleInfo).isNotEmptyFile();
        ModuleDescriptor descriptor = ModuleDescriptor.read(Files.newInputStream(moduleInfo));
        assertThat(descriptor.rawVersion()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void can_execute_javac_with_resources(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES + "sample"));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("Sample.java"))) {
            writer.append("package sample;");
            writer.newLine();
            writer.append("public class Sample { }");
            writer.newLine();
        }
        Files.writeString(folder.resolve("foo"), "bar");
        Files.createDirectory(sources.resolve(BuildStep.SOURCES + "folder"));
        BuildStepResult result = (process ? Javac.process() : Javac.tool()).apply(Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                        sources,
                        Map.of(Path.of("sources/sample/Sample.java"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Javac.CLASSES + "sample/Sample.class")).isNotEmptyFile();
        assertThat(next.resolve(Javac.CLASSES + "sample/foo")).content().isEqualTo("bar");
        assertThat(next.resolve(Javac.CLASSES + "folder")).isDirectory();
    }
}
