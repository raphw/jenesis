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
import build.jenesis.project.GroovyCompilerModule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GroovyCompilerModuleTest {

    private static final String GROOVY_VERSION = "5.0.1";

    @TempDir
    private Path root, project, compiled;

    @Test
    public void compiles_a_groovy_source_against_a_downloaded_compiler() throws IOException, ReflectiveOperationException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("groovy/maven/org.apache.groovy/groovy", GROOVY_VERSION);
        properties.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.groovy"), """
                package sample
                class Sample {
                    String greet() { "Hello world!" }
                }
                """);

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "groovy",
                new GroovyCompilerModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path classes = root
                .resolve("groovy")
                .resolve(GroovyCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        Path sampleClass = classes.resolve("sample/Sample.class");
        assertThat(sampleClass).isNotEmptyFile();

        Path artifacts = root
                .resolve("groovy")
                .resolve("dependencies")
                .resolve(GroovyCompilerModule.ARTIFACTS)
                .resolve("output")
                .resolve(BuildStep.DEPENDENCIES);
        URL[] runtimeUrls = collectJarUrls(artifacts).stream().toArray(URL[]::new);
        URL[] urls = Stream.concat(
                        Stream.of(classes.toUri().toURL()),
                        Arrays.stream(runtimeUrls))
                .toArray(URL[]::new);
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            Class<?> sample = loader.loadClass("sample.Sample");
            Object instance = sample.getConstructor().newInstance();
            Object greeting = sample.getMethod("greet").invoke(instance);
            assertThat(greeting).isEqualTo("Hello world!");
        }
    }

    @Test
    public void includeResources_false_excludes_non_groovy_non_java_files_from_output() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("groovy/maven/org.apache.groovy/groovy", GROOVY_VERSION);
        properties.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.groovy"), """
                package sample
                class Sample {}
                """);
        Files.writeString(sampleDir.resolve("app.properties"), "key=value");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "groovy",
                new GroovyCompilerModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver()))
                        .includeResources(false),
                "project");
        executor.execute();

        Path classes = root
                .resolve("groovy")
                .resolve(GroovyCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(classes.resolve("sample/Sample.class")).isNotEmptyFile();
        assertThat(classes.resolve("sample/app.properties"))
                .as("resources are excluded when includeResources(false)")
                .doesNotExist();
    }

    @Test
    public void groovy_resolves_java_types_from_the_compiled_class_path() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("groovy/maven/org.apache.groovy/groovy", GROOVY_VERSION);
        properties.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.groovy"), """
                package sample
                class Sample {
                    String greet() { new Greeter().hello() + " from Groovy" }
                }
                """);

        Path greeterSource = Files
                .createDirectories(compiled.resolve("src").resolve("sample"))
                .resolve("Greeter.java");
        Files.writeString(greeterSource, """
                package sample;
                public class Greeter {
                    public String hello() { return "Hello"; }
                }
                """);
        Path greeterClasses = Files.createDirectories(compiled.resolve(GroovyCompilerModule.CLASSES));
        ToolProvider javac = ToolProvider.findFirst("javac").orElseThrow();
        int code = javac.run(System.out, System.err, "-d", greeterClasses.toString(), greeterSource.toString());
        assertThat(code).isZero();

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addSource("classes", compiled);
        executor.addModule(
                "groovy",
                new GroovyCompilerModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project", "classes");
        executor.execute();

        Path classes = root
                .resolve("groovy")
                .resolve(GroovyCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(classes.resolve("sample/Sample.class"))
                .as("Sample.groovy resolved the Greeter type from the compiled class path and compiled")
                .isNotEmptyFile();
        Path groovycOutput = root
                .resolve("groovy")
                .resolve("compiled")
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(groovycOutput.resolve("sample/Greeter.class"))
                .as("groovyc compiles only Groovy sources; the Java type comes from the class path, not recompilation")
                .doesNotExist();
    }

    @Test
    public void requires_step_picks_module_coordinate_when_module_resolver_available() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "groovy",
                new GroovyCompilerModule(
                        Map.of(),
                        Map.of("module", (_, _, _, _, _, _, _) -> new LinkedHashMap<>())),
                "project");
        executor.execute("groovy/" + "required");

        Path requiredOutput = root.resolve("groovy").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactly("groovy/module/org.apache.groovy");
        assertThat(requiredOutput.resolve(BuildStep.VERSIONS)).doesNotExist();
    }

    @Test
    public void requires_step_picks_maven_coordinate_when_only_maven_resolver_available() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "groovy",
                new GroovyCompilerModule(
                        Map.of(),
                        Map.of("maven", (_, _, _, _, _, _, _) -> new LinkedHashMap<>())),
                "project");
        executor.execute("groovy/" + "required");

        Path requiredOutput = root.resolve("groovy").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactly("groovy/maven/org.apache.groovy/groovy/RELEASE");
        assertThat(requiredOutput.resolve(BuildStep.VERSIONS)).doesNotExist();
    }

    @Test
    public void requires_step_fails_when_no_supported_prefix_is_registered() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "groovy",
                new GroovyCompilerModule(
                        Map.of(),
                        Map.of("vendor", (_, _, _, _, _, _, _) -> new LinkedHashMap<>())),
                "project");

        assertThatThrownBy(() -> executor.execute("groovy/" + "required"))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("No suitable resolver");
    }

    @Test
    public void exposes_resolved_alongside_classes_so_coordinates_reach_the_pin_stage() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "groovy",
                new GroovyCompilerModule(
                        Map.of(),
                        Map.of("maven", (_, _, _, _, _, _, _) -> new LinkedHashMap<>())),
                "project");
        SequencedMap<String, Path> outputs = executor.execute();

        assertThat(outputs).containsKeys("groovy/classes", "groovy/resolved");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
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
}
