package build.jenesis.test.step;

import build.jenesis.PathPlacement;
import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Launcher;

import static org.assertj.core.api.Assertions.assertThat;

public class LauncherTest {

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
    public void shades_the_launcher_and_explodes_the_class_path() throws IOException {
        writeLauncherJar(Files.createDirectory(input.resolve("resolved")).resolve("launcher.jar"));
        writeJar(Files.createDirectory(input.resolve(BuildStep.ARTIFACTS)).resolve("app.jar"), "sample/Sample.class");
        writeJar(input.resolve("resolved").resolve("lib.jar"), "lib/Lib.class");
        SequencedProperties index = new SequencedProperties();
        index.setProperty("launcher/runtime/maven/build.jenesis/build.jenesis.launcher", "resolved/launcher.jar");
        index.setProperty("main/runtime/maven/lib", "resolved/lib.jar");
        index.store(input.resolve(BuildStep.DEPENDENCIES));
        SequencedProperties application = new SequencedProperties();
        application.setProperty("mainClass", "sample.Sample");
        application.setProperty("name", "app");
        application.store(input.resolve("launcher.properties"));

        BuildStepResult result = new Launcher("launcher", PathPlacement.INFERRED).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("input", new BuildStepArgument(
                        input,
                        Map.of(Path.of("resolved/launcher.jar"), Checksum.of(ChecksumStatus.ADDED),
                                Path.of("artifacts/app.jar"), Checksum.of(ChecksumStatus.ADDED),
                                Path.of("resolved/lib.jar"), Checksum.of(ChecksumStatus.ADDED),
                                Path.of("launcher.properties"), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();

        assertThat(result.next()).isTrue();
        Path jar = next.resolve(Launcher.LAUNCHER).resolve("app.jar");
        assertThat(jar).isRegularFile();
        SequencedSet<String> entries = entries(jar);
        assertThat(entries)
                .as("the launcher classes are shaded into the root, without its module-info or manifest")
                .contains("build/jenesis/launcher/Launcher.class")
                .doesNotContain("module-info.class", "build/jenesis/launcher/module-info.class");
        assertThat(entries).contains(
                "application.properties",
                "classpath/app.jar/sample/Sample.class",
                "classpath/lib.jar/lib/Lib.class");
        assertThat(entries).noneMatch(name -> name.startsWith("modulepath/"));
        assertThat(mainClass(jar)).isEqualTo("build.jenesis.launcher.Launcher");
        Properties descriptor = application(jar);
        assertThat(descriptor.getProperty("mainClass")).isEqualTo("sample.Sample");
        assertThat(descriptor.getProperty("mainModule")).isNull();
        assertThat(descriptor.getProperty("classpath")).isEqualTo("app.jar,lib.jar");
    }

    @Test
    public void routes_a_modular_main_onto_the_module_path() throws IOException {
        writeLauncherJar(Files.createDirectory(input.resolve("resolved")).resolve("launcher.jar"));
        compileModularJar(Files.createDirectory(input.resolve(BuildStep.ARTIFACTS)).resolve("sample.jar"));
        SequencedProperties index = new SequencedProperties();
        index.setProperty("launcher/runtime/maven/build.jenesis/build.jenesis.launcher", "resolved/launcher.jar");
        index.store(input.resolve(BuildStep.DEPENDENCIES));
        SequencedProperties application = new SequencedProperties();
        application.setProperty("mainClass", "sample.Sample");
        application.setProperty("mainModule", "sample");
        application.setProperty("name", "sample");
        application.store(input.resolve("launcher.properties"));

        BuildStepResult result = new Launcher("launcher", PathPlacement.INFERRED).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("input", new BuildStepArgument(
                        input,
                        Map.of(Path.of("resolved/launcher.jar"), Checksum.of(ChecksumStatus.ADDED),
                                Path.of("artifacts/sample.jar"), Checksum.of(ChecksumStatus.ADDED),
                                Path.of("launcher.properties"), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();

        assertThat(result.next()).isTrue();
        Path jar = next.resolve(Launcher.LAUNCHER).resolve("sample.jar");
        SequencedSet<String> entries = entries(jar);
        assertThat(entries).contains("modulepath/sample.jar/sample/Sample.class", "modulepath/sample.jar/module-info.class");
        assertThat(entries).noneMatch(name -> name.startsWith("classpath/"));
        Properties descriptor = application(jar);
        assertThat(descriptor.getProperty("mainModule")).isEqualTo("sample");
        assertThat(descriptor.getProperty("classpath")).isNull();
    }

    @Test
    public void honours_a_class_path_placement_for_a_modular_main() throws IOException {
        writeLauncherJar(Files.createDirectory(input.resolve("resolved")).resolve("launcher.jar"));
        compileModularJar(Files.createDirectory(input.resolve(BuildStep.ARTIFACTS)).resolve("sample.jar"));
        SequencedProperties index = new SequencedProperties();
        index.setProperty("launcher/runtime/maven/build.jenesis/build.jenesis.launcher", "resolved/launcher.jar");
        index.store(input.resolve(BuildStep.DEPENDENCIES));
        SequencedProperties application = new SequencedProperties();
        application.setProperty("mainClass", "sample.Sample");
        application.setProperty("mainModule", "sample");
        application.setProperty("name", "sample");
        application.store(input.resolve("launcher.properties"));

        BuildStepResult result = new Launcher("launcher", PathPlacement.CLASS_PATH).apply(
                Runnable::run,
                new BuildStepContext(previous, next, supplement),
                new LinkedHashMap<>(Map.of("input", new BuildStepArgument(
                        input,
                        Map.of(Path.of("resolved/launcher.jar"), Checksum.of(ChecksumStatus.ADDED),
                                Path.of("artifacts/sample.jar"), Checksum.of(ChecksumStatus.ADDED),
                                Path.of("launcher.properties"), Checksum.of(ChecksumStatus.ADDED)))))).toCompletableFuture().join();

        assertThat(result.next()).isTrue();
        Path jar = next.resolve(Launcher.LAUNCHER).resolve("sample.jar");
        SequencedSet<String> entries = entries(jar);
        assertThat(entries)
                .as("a CLASS_PATH placement keeps even a modular jar on the class path, not the module path")
                .contains("classpath/sample.jar/sample/Sample.class", "classpath/sample.jar/module-info.class");
        assertThat(entries).noneMatch(name -> name.startsWith("modulepath/"));
        assertThat(application(jar).getProperty("classpath")).isEqualTo("sample.jar");
    }

    private static void writeLauncherJar(Path path) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            entry(jar, "module-info.class");
            entry(jar, "build/jenesis/launcher/Launcher.class");
        }
    }

    private static void writeJar(Path path, String entry) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            entry(jar, entry);
        }
    }

    private void compileModularJar(Path path) throws IOException {
        Path sources = Files.createDirectory(root.resolve("module-sources"));
        Files.writeString(sources.resolve("module-info.java"), "module sample { }\n");
        Files.writeString(Files.createDirectory(sources.resolve("sample")).resolve("Sample.java"),
                "package sample; public class Sample { public static void main(String[] args) { } }\n");
        Path classes = Files.createDirectory(root.resolve("module-classes"));
        assertThat(ToolProvider.findFirst("javac").orElseThrow().run(System.out, System.err,
                "-d", classes.toString(),
                sources.resolve("module-info.java").toString(),
                sources.resolve("sample/Sample.java").toString())).isZero();
        assertThat(ToolProvider.findFirst("jar").orElseThrow().run(System.out, System.err,
                "--create", "--file", path.toString(), "-C", classes.toString(), ".")).isZero();
    }

    private static void entry(JarOutputStream jar, String name) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(new byte[] {1, 2, 3});
        jar.closeEntry();
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

    private static String mainClass(Path zip) throws IOException {
        try (JarFile file = new JarFile(zip.toFile())) {
            return file.getManifest().getMainAttributes().getValue("Main-Class");
        }
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
