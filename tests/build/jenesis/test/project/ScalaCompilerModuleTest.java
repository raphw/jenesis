package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.Repository;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.project.ScalaCompilerModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ScalaCompilerModuleTest {

    private static final String SCALA_VERSION = "3.5.2";

    @TempDir
    private Path root, project;

    @Test
    public void compiles_a_scala_source_against_a_downloaded_compiler() throws IOException, ReflectiveOperationException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("version", SCALA_VERSION);
        properties.store(project.resolve("scala.properties"));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.scala"), """
                package sample
                class Sample:
                  def greet(): String = "Hello world!"
                """);

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "scala",
                new ScalaCompilerModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path classes = root
                .resolve("scala")
                .resolve(ScalaCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        Path sampleClass = classes.resolve("sample/Sample.class");
        assertThat(sampleClass).isNotEmptyFile();

        Path artifacts = root
                .resolve("scala")
                .resolve(ScalaCompilerModule.ARTIFACTS)
                .resolve("output")
                .resolve(BuildStep.DEPENDENCIES);
        URL[] runtimeUrls = collectJarUrls(artifacts).stream().toArray(URL[]::new);
        URL[] urls = Stream.concat(
                        Stream.of(classes.toUri().toURL()),
                        Arrays.stream(runtimeUrls))
                .toArray(URL[]::new);
        URLClassLoader loader = new URLClassLoader(urls, null);
        Class<?> sample = loader.loadClass("sample.Sample");
        Object instance = sample.getConstructor().newInstance();
        Object greeting = sample.getMethod("greet").invoke(instance);
        assertThat(greeting).isEqualTo("Hello world!");
    }

    @Test
    public void scala_can_reference_java_sources_supplied_to_the_same_step() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("version", SCALA_VERSION);
        properties.store(project.resolve("scala.properties"));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Greeter.java"), """
                package sample;
                public class Greeter {
                    public String hello() { return "Hello"; }
                }
                """);
        Files.writeString(sampleDir.resolve("Sample.scala"), """
                package sample
                class Sample:
                  def greet(): String = Greeter().hello() + " from Scala"
                """);

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "scala",
                new ScalaCompilerModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path classes = root
                .resolve("scala")
                .resolve(ScalaCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(classes.resolve("sample/Sample.class"))
                .as("Sample.scala resolved the Greeter type from the .java source companion and compiled")
                .isNotEmptyFile();
    }

    @Test
    public void downloads_the_full_compiler_dependency_set_from_the_scala_pom() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("version", SCALA_VERSION);
        properties.store(project.resolve("scala.properties"));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.scala"), "package sample\nclass Sample\n");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "scala",
                new ScalaCompilerModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path artifacts = root
                .resolve("scala")
                .resolve(ScalaCompilerModule.ARTIFACTS)
                .resolve("output")
                .resolve(BuildStep.DEPENDENCIES);
        List<String> names = listJarNames(artifacts);
        assertThat(names).anyMatch(name -> name.contains("scala3-compiler_3"));
        assertThat(names).anyMatch(name -> name.contains("scala3-library_3"));
        assertThat(names).anyMatch(name -> name.contains("scala3-interfaces"));
        assertThat(names).anyMatch(name -> name.contains("tasty-core_3"));
        assertThat(names).anyMatch(name -> name.contains("scala-library"));
        assertThat(names).anyMatch(name -> name.contains("scala-asm"));
        assertThat(names).anyMatch(name -> name.contains("compiler-interface"));
    }

    @Test
    public void requires_step_picks_module_coordinate_when_module_resolver_available() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("version", SCALA_VERSION);
        properties.store(project.resolve("scala.properties"));

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "scala",
                new ScalaCompilerModule(
                        Map.of(),
                        Map.of("module", (_, _, _, _, _, _) -> new LinkedHashMap<>())),
                "project");
        executor.execute("scala/" + "required");

        Path requiredOutput = root.resolve("scala").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactly("module/org.scala.lang.scala3.compiler");
        SequencedProperties versions = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.VERSIONS));
        assertThat(versions).containsEntry("module/org.scala.lang.scala3.compiler", SCALA_VERSION);
    }

    @Test
    public void requires_step_picks_maven_coordinate_when_only_maven_resolver_available() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("version", SCALA_VERSION);
        properties.store(project.resolve("scala.properties"));

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "scala",
                new ScalaCompilerModule(
                        Map.of(),
                        Map.of("maven", (_, _, _, _, _, _) -> new LinkedHashMap<>())),
                "project");
        executor.execute("scala/" + "required");

        Path requiredOutput = root.resolve("scala").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactly("maven/org.scala-lang/scala3-compiler_3/" + SCALA_VERSION);
        assertThat(requiredOutput.resolve(BuildStep.VERSIONS)).doesNotExist();
    }

    @Test
    public void requires_step_prefers_module_over_maven_when_both_available() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("version", SCALA_VERSION);
        properties.store(project.resolve("scala.properties"));

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "scala",
                new ScalaCompilerModule(
                        Map.of(),
                        Map.of(
                                "module", (_, _, _, _, _, _) -> new LinkedHashMap<>(),
                                "maven", (_, _, _, _, _, _) -> new LinkedHashMap<>())),
                "project");
        executor.execute("scala/" + "required");

        Path requiredOutput = root.resolve("scala").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .singleElement()
                .satisfies(name -> assertThat(name).startsWith("module/"));
    }

    @Test
    public void requires_step_fails_when_no_supported_prefix_is_registered() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("version", SCALA_VERSION);
        properties.store(project.resolve("scala.properties"));

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "scala",
                new ScalaCompilerModule(
                        Map.of(),
                        Map.of("vendor", (_, _, _, _, _, _) -> new LinkedHashMap<>())),
                "project");

        assertThatThrownBy(() -> executor.execute("scala/" + "required"))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("No suitable resolver");
    }

    @Test
    public void fails_when_no_scala_properties_is_present_upstream() throws IOException {
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.scala"), "package sample\nclass Sample\n");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "scala",
                new ScalaCompilerModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");

        assertThatThrownBy(executor::execute)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("scala.properties");
    }

    @Test
    public void fails_when_scala_properties_lacks_version() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("foo", "bar");
        properties.store(project.resolve("scala.properties"));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.scala"), "package sample\nclass Sample\n");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "scala",
                new ScalaCompilerModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");

        assertThatThrownBy(executor::execute)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("Missing 'version'");
    }

    @Test
    public void rejects_scala_2_version_in_scala_properties() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("version", "2.13.16");
        properties.store(project.resolve("scala.properties"));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.scala"), "package sample\nclass Sample\n");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "scala",
                new ScalaCompilerModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");

        assertThatThrownBy(executor::execute)
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("Only Scala 3 is supported")
                .hasMessageContaining("2.13.16");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop());
    }

    private static Repository mavenCentral() {
        Path local = Path.of(System.getProperty("user.home"), ".m2", "repository");
        return new MavenDefaultRepository(
                URI.create("https://repo1.maven.org/maven2/"),
                Files.isDirectory(local) ? local : null,
                Map.of(),
                _ -> {});
    }

    private static List<URL> collectJarUrls(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<URL> urls = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.jar")) {
            for (Path file : stream) {
                urls.add(file.toUri().toURL());
            }
        }
        return urls;
    }

    private static List<String> listJarNames(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.jar")) {
            for (Path file : stream) {
                names.add(file.getFileName().toString());
            }
        }
        return names;
    }
}
