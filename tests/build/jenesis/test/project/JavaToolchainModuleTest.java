package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.BuildStepResult;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.project.JavaToolchainModule;
import build.jenesis.project.TestModule;
import sample.Sample;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JavaToolchainModuleTest {

    @TempDir
    private Path input, root;
    private BuildExecutor buildExecutor;

    @BeforeEach
    public void setUp() throws Exception {
        buildExecutor = BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
    }

    @Test
    public void can_build_java() throws IOException {
        Path sources = Files.createDirectories(input.resolve(BuildStep.SOURCES + "other"));
        try (BufferedWriter writer = Files.newBufferedWriter(sources.resolve("Sample.java"))) {
            writer.append("package other;");
            writer.newLine();
            writer.append("public class Sample {");
            writer.newLine();
            writer.append("  sample.Sample s = new sample.Sample();");
            writer.newLine();
            writer.append("}");
            writer.newLine();
        }
        try (JarOutputStream outputStream = new JarOutputStream(Files.newOutputStream(Files
                .createDirectories(input.resolve(BuildStep.ARTIFACTS))
                .resolve("dependency.jar")));
             InputStream inputStream = requireNonNull(Sample.class.getResourceAsStream("Sample.class"))) {
            outputStream.putNextEntry(new JarEntry("sample/Sample.class"));
            inputStream.transferTo(outputStream);
        }
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("compile/local/sample", BuildStep.ARTIFACTS + "dependency.jar");
        dependencies.store(input.resolve(BuildStep.DEPENDENCIES));
        buildExecutor.addSource("input", input);
        buildExecutor.addModule("output", new JavaToolchainModule(), "input");
        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps).containsKeys("output/classes", "output/artifacts");
        assertThat(steps.get("output/classes").resolve(BuildStep.CLASSES).resolve("other/Sample.class")).exists();
        try (JarInputStream inputStream = new JarInputStream(Files.newInputStream(steps.get("output/artifacts")
                .resolve(BuildStep.ARTIFACTS)
                .resolve("classes.jar")))) {
            assertThat(inputStream.getNextJarEntry())
                    .extracting(ZipEntry::getName)
                    .isEqualTo("other/");
            assertThat(inputStream.getNextJarEntry())
                    .extracting(ZipEntry::getName)
                    .isEqualTo("other/Sample.class");
            assertThat(inputStream.getNextJarEntry()).isNull();
        }
    }

    @Test
    public void transformer_rewrites_classes_before_archiving() throws IOException {
        Path sources = Files.createDirectories(input.resolve(BuildStep.SOURCES + "other"));
        try (BufferedWriter writer = Files.newBufferedWriter(sources.resolve("Sample.java"))) {
            writer.append("package other;");
            writer.newLine();
            writer.append("public class Sample { }");
            writer.newLine();
        }
        buildExecutor.addSource("input", input);
        buildExecutor.addModule("output", new JavaToolchainModule().transformer(TRANSFORMER), "input");
        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps).containsKeys("output/classes", "output/artifacts");
        assertThat(steps.get("output/classes").resolve(BuildStep.CLASSES).resolve("transformed.marker")).exists();
        try (JarInputStream inputStream = new JarInputStream(Files.newInputStream(steps.get("output/artifacts")
                .resolve(BuildStep.ARTIFACTS)
                .resolve("classes.jar")))) {
            Set<String> names = new LinkedHashSet<>();
            for (JarEntry entry = inputStream.getNextJarEntry(); entry != null; entry = inputStream.getNextJarEntry()) {
                names.add(entry.getName());
            }
            assertThat(names).contains("other/Sample.class", "transformed.marker");
        }
    }

    @Test
    public void validator_consumes_classes_as_independent_side_step() throws IOException {
        Path sources = Files.createDirectories(input.resolve(BuildStep.SOURCES + "other"));
        try (BufferedWriter writer = Files.newBufferedWriter(sources.resolve("Sample.java"))) {
            writer.append("package other;");
            writer.newLine();
            writer.append("public class Sample { }");
            writer.newLine();
        }
        buildExecutor.addSource("input", input);
        buildExecutor.addModule("output", new JavaToolchainModule().validator(VALIDATOR), "input");
        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps).containsKeys("output/classes", "output/artifacts", "output/validate");
        assertThat(steps.get("output/validate").resolve("validated").resolve("classes.txt"))
                .hasContent("other/Sample.class");
    }

    @Test
    public void test_with_require_engine_throws_when_no_engine_resolves() throws IOException {
        Path sources = Files.createDirectories(input.resolve(BuildStep.SOURCES + "other"));
        try (BufferedWriter writer = Files.newBufferedWriter(sources.resolve("Sample.java"))) {
            writer.append("package other;");
            writer.newLine();
            writer.append("public class Sample { }");
            writer.newLine();
        }
        buildExecutor.addSource("input", input);
        buildExecutor.addModule("output", new JavaToolchainModule(), "input");
        buildExecutor.addModule("output-test", new TestModule(Map.of(), Map.of()).requireEngine(true), "output", "input");
        assertThatThrownBy(() -> buildExecutor.execute())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("No test engine could be resolved from inherited dependencies");
    }

    @Test
    public void test_without_require_engine_silently_skips_when_no_engine_resolves() throws IOException {
        Path sources = Files.createDirectories(input.resolve(BuildStep.SOURCES + "other"));
        try (BufferedWriter writer = Files.newBufferedWriter(sources.resolve("Sample.java"))) {
            writer.append("package other;");
            writer.newLine();
            writer.append("public class Sample { }");
            writer.newLine();
        }
        buildExecutor.addSource("input", input);
        buildExecutor.addModule("output", new JavaToolchainModule(), "input");
        buildExecutor.addModule("output-test", new TestModule(Map.of(), Map.of()).requireEngine(false), "output", "input");
        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps).containsKeys("output/classes", "output/artifacts");
        assertThat(steps).doesNotContainKey("output-test/executed");
    }

    @Test
    public void can_build_java_with_junit() throws Exception {
        Path sources = Files.createDirectories(input.resolve(BuildStep.SOURCES + "other"));
        try (BufferedWriter writer = Files.newBufferedWriter(sources.resolve("SampleTest.java"))) {
            writer.append("package other;");
            writer.newLine();
            writer.append("public class SampleTest {");
            writer.newLine();
            writer.append("  @org.junit.jupiter.api.Test");
            writer.newLine();
            writer.append("  public void test() {");
            writer.newLine();
            writer.append("    System.out.println(\"Hello world!\");");
            writer.newLine();
            writer.append("  }");
            writer.newLine();
            writer.append("}");
            writer.newLine();
        }
        Path artifacts = Files.createDirectory(input.resolve(BuildStep.ARTIFACTS));
        SequencedProperties dependencies = new SequencedProperties();
        int index = 0;
        for (Path path : bootModuleJars()) {
            String fileName = path.getFileName().toString();
            if (fileName.endsWith("_rt.jar") || fileName.endsWith("-rt.jar")) {
                continue;
            }
            String jarName = URLEncoder.encode(UUID.randomUUID().toString(), StandardCharsets.UTF_8) + ".jar";
            Files.copy(path, artifacts.resolve(jarName));
            dependencies.setProperty("compile/boot/jar" + index++, BuildStep.ARTIFACTS + jarName);
        }
        dependencies.store(input.resolve(BuildStep.DEPENDENCIES));
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("main/runtime/maven/org.junit.platform/junit-platform-console",
                "1.11.4 SHA-256/a9c3309cdfded3542200de85da6cb274864439d6b02ba80bb45ecc8e0bdf1be7");
        versions.store(input.resolve(BuildStep.VERSIONS));
        buildExecutor.addSource("input", input);
        buildExecutor.addModule("output", new JavaToolchainModule(), "input");
        buildExecutor.addModule("output-test", new TestModule(
                Map.of("maven", new MavenDefaultRepository(
                        URI.create("https://repo1.maven.org/maven2/"),
                        null,
                        Map.of(),
                        _ -> {})),
                Map.of("maven", new MavenPomResolver())), "output", "input");
        SequencedMap<String, Path> steps = buildExecutor.execute();
        assertThat(steps).containsKeys("output/classes", "output/artifacts", "output-test/executed");
        assertThat(steps.get("output/classes").resolve(BuildStep.CLASSES).resolve("other/SampleTest.class")).exists();
        try (JarInputStream inputStream = new JarInputStream(Files.newInputStream(steps.get("output/artifacts")
                .resolve(BuildStep.ARTIFACTS)
                .resolve("classes.jar")))) {
            assertThat(inputStream.getNextJarEntry())
                    .extracting(ZipEntry::getName)
                    .isEqualTo("other/");
            assertThat(inputStream.getNextJarEntry())
                    .extracting(ZipEntry::getName)
                    .isEqualTo("other/SampleTest.class");
            assertThat(inputStream.getNextJarEntry()).isNull();
        }
    }

    private static final BuildExecutorModule TRANSFORMER = ((BuildStep) (_, context, arguments) -> {
        Path classes = Files.createDirectories(context.next().resolve(BuildStep.CLASSES));
        for (BuildStepArgument argument : arguments.values()) {
            Path incoming = argument.folder().resolve(BuildStep.CLASSES);
            if (Files.isDirectory(incoming)) {
                try (Stream<Path> stream = Files.walk(incoming)) {
                    List<Path> files = stream.filter(Files::isRegularFile).toList();
                    for (Path file : files) {
                        Path target = classes.resolve(incoming.relativize(file).toString());
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target);
                    }
                }
            }
        }
        Files.writeString(classes.resolve("transformed.marker"), "transformed");
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }).asModule("transform");

    private static final BuildExecutorModule VALIDATOR = ((BuildStep) (_, context, arguments) -> {
        List<String> names = new ArrayList<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path incoming = argument.folder().resolve(BuildStep.CLASSES);
            if (Files.isDirectory(incoming)) {
                try (Stream<Path> stream = Files.walk(incoming)) {
                    stream.filter(Files::isRegularFile)
                            .map(path -> incoming.relativize(path).toString().replace(File.separatorChar, '/'))
                            .forEach(names::add);
                }
            }
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("No classes to validate");
        }
        Path report = Files.createDirectories(context.next().resolve("validated"));
        Files.writeString(report.resolve("classes.txt"), String.join("\n", names));
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }).asModule("validate");

    private static List<Path> bootModuleJars() throws IOException {
        Set<Path> jars = new LinkedHashSet<>();
        for (ResolvedModule resolved : ModuleLayer.boot().configuration().modules()) {
            String name = resolved.name();
            if (name.startsWith("java.") || name.startsWith("jdk.")) {
                continue;
            }
            URI location = resolved.reference().location().orElse(null);
            if (location == null) {
                continue;
            }
            Path path = Path.of(location);
            if (Files.isRegularFile(path)) {
                jars.add(path);
            }
        }
        Enumeration<URL> manifests = ClassLoader.getSystemClassLoader().getResources("META-INF/MANIFEST.MF");
        while (manifests.hasMoreElements()) {
            String url = manifests.nextElement().toString();
            if (!url.startsWith("jar:file:")) {
                continue;
            }
            int bang = url.indexOf("!/");
            if (bang < 0) {
                continue;
            }
            Path path = Path.of(URI.create(url.substring("jar:".length(), bang)));
            if (Files.isRegularFile(path)) {
                jars.add(path);
            }
        }
        return new ArrayList<>(jars);
    }
}
