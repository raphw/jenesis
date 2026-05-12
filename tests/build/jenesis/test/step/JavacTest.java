package build.jenesis.test.step;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.ChecksumStatus;
import build.jenesis.step.Javac;

import module java.base;
import module org.junit.jupiter.api;
import module org.junit.jupiter.params;

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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void stamps_module_version_when_jenesis_buildVersion_is_set(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("module-info.java"))) {
            writer.append("module sample { }");
            writer.newLine();
        }
        String previous = System.getProperty("jenesis.buildVersion");
        System.setProperty("jenesis.buildVersion", "1.2.3");
        try {
            BuildStepResult result = (process ? Javac.process() : Javac.tool()).apply(Runnable::run,
                    new BuildStepContext(this.previous, next, supplement),
                    new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                            sources,
                            Map.of(Path.of("sources/module-info.java"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
            assertThat(result.next()).isTrue();
            Path moduleInfo = next.resolve(Javac.CLASSES + "module-info.class");
            assertThat(moduleInfo).isNotEmptyFile();
            ModuleDescriptor descriptor = ModuleDescriptor.read(Files.newInputStream(moduleInfo));
            assertThat(descriptor.rawVersion()).contains("1.2.3");
        } finally {
            if (previous == null) {
                System.clearProperty("jenesis.buildVersion");
            } else {
                System.setProperty("jenesis.buildVersion", previous);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void does_not_stamp_module_version_when_jenesis_buildVersion_is_absent(boolean process) throws IOException {
        Path folder = Files.createDirectories(sources.resolve(BuildStep.SOURCES));
        try (BufferedWriter writer = Files.newBufferedWriter(folder.resolve("module-info.java"))) {
            writer.append("module sample { }");
            writer.newLine();
        }
        String previous = System.getProperty("jenesis.buildVersion");
        System.clearProperty("jenesis.buildVersion");
        try {
            BuildStepResult result = (process ? Javac.process() : Javac.tool()).apply(Runnable::run,
                    new BuildStepContext(this.previous, next, supplement),
                    new LinkedHashMap<>(Map.of("sources", new BuildStepArgument(
                            sources,
                            Map.of(Path.of("sources/module-info.java"), ChecksumStatus.ADDED))))).toCompletableFuture().join();
            assertThat(result.next()).isTrue();
            Path moduleInfo = next.resolve(Javac.CLASSES + "module-info.class");
            assertThat(moduleInfo).isNotEmptyFile();
            ModuleDescriptor descriptor = ModuleDescriptor.read(Files.newInputStream(moduleInfo));
            assertThat(descriptor.rawVersion()).isEmpty();
        } finally {
            if (previous != null) {
                System.setProperty("jenesis.buildVersion", previous);
            }
        }
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
