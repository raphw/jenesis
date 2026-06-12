package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Bundle;

import static org.assertj.core.api.Assertions.assertThat;

public class BundleTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, input;

    @BeforeEach
    public void setUp() throws IOException {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        input = Files.createDirectory(root.resolve("input"));
    }

    @Test
    public void bundles_a_non_modular_main_onto_the_class_path() throws IOException {
        writePlainJar(Files.createDirectory(input.resolve(BuildStep.ARTIFACTS)).resolve("app.jar"));
        writePlainJar(Files.createDirectory(input.resolve("resolved")).resolve("lib.jar"));
        SequencedProperties index = new SequencedProperties();
        index.setProperty("main/runtime/maven/lib", "resolved/lib.jar");
        index.store(input.resolve(BuildStep.DEPENDENCIES));
        SequencedProperties launcher = new SequencedProperties();
        launcher.setProperty("--main-jar", "app.jar");
        launcher.setProperty("--main-class", "sample.Sample");
        launcher.store(Files.createDirectory(input.resolve("process")).resolve("jpackage.properties"));

        BuildStepResult result = new Bundle().apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("input", new BuildStepArgument(
                        input,
                        Map.of(Path.of("artifacts/app.jar"), Checksum.of(ChecksumStatus.ADDED),
                                Path.of("resolved/lib.jar"), Checksum.of(ChecksumStatus.ADDED),
                                Path.of("process/jpackage.properties"), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();

        assertThat(result.next()).isTrue();
        Path zip = next.resolve(Bundle.BUNDLE).resolve("bundle.zip");
        assertThat(zip).isRegularFile();
        SequencedSet<String> entries = entries(zip);
        assertThat(entries).contains("application.properties", "classpath/app.jar", "classpath/lib.jar");
        assertThat(entries).noneMatch(name -> name.startsWith("modulepath/"));
        Properties application = application(zip);
        assertThat(application.getProperty("mainClass")).isEqualTo("sample.Sample");
        assertThat(application.getProperty("mainModule")).isNull();
    }

    @Test
    public void bundles_a_modular_main_onto_the_module_path() throws IOException {
        Path sources = Files.createDirectory(root.resolve("sources"));
        Files.writeString(sources.resolve("module-info.java"), "module sample { }\n");
        Files.writeString(Files.createDirectory(sources.resolve("sample")).resolve("Sample.java"),
                "package sample; public class Sample { public static void main(String[] args) { } }\n");
        Path classes = Files.createDirectory(root.resolve("classes"));
        assertThat(ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                "-d", classes.toString(),
                sources.resolve("module-info.java").toString(),
                sources.resolve("sample/Sample.java").toString())).isZero();
        assertThat(ToolProvider.findFirst("jar").orElseThrow().run(System.out, System.err,
                "--create", "--file",
                Files.createDirectory(input.resolve(BuildStep.ARTIFACTS)).resolve("sample.jar").toString(),
                "-C", classes.toString(), ".")).isZero();
        SequencedProperties launcher = new SequencedProperties();
        launcher.setProperty("--module", "sample/sample.Sample");
        launcher.store(Files.createDirectory(input.resolve("process")).resolve("jpackage.properties"));

        BuildStepResult result = new Bundle().apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("input", new BuildStepArgument(
                        input,
                        Map.of(Path.of("artifacts/sample.jar"), Checksum.of(ChecksumStatus.ADDED),
                                Path.of("process/jpackage.properties"), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();

        assertThat(result.next()).isTrue();
        Path zip = next.resolve(Bundle.BUNDLE).resolve("bundle.zip");
        SequencedSet<String> entries = entries(zip);
        assertThat(entries).contains("application.properties", "modulepath/sample.jar");
        assertThat(entries).noneMatch(name -> name.startsWith("classpath/"));
        Properties application = application(zip);
        assertThat(application.getProperty("mainClass")).isEqualTo("sample.Sample");
        assertThat(application.getProperty("mainModule")).isEqualTo("sample");
    }

    @Test
    public void skips_a_module_without_a_main() throws IOException {
        writePlainJar(Files.createDirectory(input.resolve(BuildStep.ARTIFACTS)).resolve("app.jar"));

        BuildStepResult result = new Bundle().apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("input", new BuildStepArgument(
                        input,
                        Map.of(Path.of("artifacts/app.jar"), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();

        assertThat(result.next()).isTrue();
        assertThat(next.resolve(Bundle.BUNDLE)).doesNotExist();
    }

    private static void writePlainJar(Path path) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry("sample/Sample.class"));
            jar.write(new byte[] {1, 2, 3});
            jar.closeEntry();
        }
    }

    private static SequencedSet<String> entries(Path zip) throws IOException {
        SequencedSet<String> names = new LinkedHashSet<>();
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> enumeration = file.entries();
            while (enumeration.hasMoreElements()) {
                names.add(enumeration.nextElement().getName());
            }
        }
        return names;
    }

    private static Properties application(Path zip) throws IOException {
        try (ZipFile file = new ZipFile(zip.toFile())) {
            Properties properties = new Properties();
            try (InputStream in = file.getInputStream(file.getEntry("application.properties"))) {
                properties.load(in);
            }
            return properties;
        }
    }
}
