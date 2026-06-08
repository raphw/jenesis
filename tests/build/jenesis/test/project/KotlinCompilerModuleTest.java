package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.project.KotlinCompilerModule;
import build.jenesis.step.Dependencies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class KotlinCompilerModuleTest {

    private static final String KOTLIN_VERSION = "2.2.0";

    @TempDir
    private Path root, project;

    @Test
    public void compiles_a_kotlin_source_against_a_downloaded_compiler() throws IOException, ReflectiveOperationException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("kotlinc/kotlinc/maven/org.jetbrains.kotlin/kotlin-compiler-embeddable", KOTLIN_VERSION);
        properties.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.kt"), """
                package sample
                class Sample {
                    fun greet(): String = "Hello world!"
                }
                """);

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "kotlin",
                new KotlinCompilerModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path classes = root
                .resolve("kotlin")
                .resolve(KotlinCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        Path sampleClass = classes.resolve("sample/Sample.class");
        assertThat(sampleClass).isNotEmptyFile();

        Path artifacts = root
                .resolve("kotlin")
                .resolve("dependencies")
                .resolve("output");
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
    public void includeResources_false_excludes_non_kotlin_non_java_files_from_output() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("kotlinc/kotlinc/maven/org.jetbrains.kotlin/kotlin-compiler-embeddable", KOTLIN_VERSION);
        properties.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.kt"), """
                package sample
                class Sample
                """);
        Files.writeString(sampleDir.resolve("app.properties"), "key=value");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "kotlin",
                new KotlinCompilerModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver()))
                        .includeResources(false),
                "project");
        executor.execute();

        Path classes = root
                .resolve("kotlin")
                .resolve(KotlinCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(classes.resolve("sample/Sample.class")).isNotEmptyFile();
        assertThat(classes.resolve("sample/app.properties"))
                .as("resources are excluded when includeResources(false)")
                .doesNotExist();
    }

    @Test
    public void skips_module_info_so_kotlinc_does_not_parse_it() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("kotlinc/kotlinc/maven/org.jetbrains.kotlin/kotlin-compiler-embeddable", KOTLIN_VERSION);
        properties.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.kt"), """
                package sample
                class Sample
                """);
        Files.writeString(project.resolve(BuildStep.SOURCES + "module-info.java"), """
                module sample {
                    exports sample;
                }
                """);

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "kotlin",
                new KotlinCompilerModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path classes = root
                .resolve("kotlin")
                .resolve(KotlinCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(classes.resolve("sample/Sample.class"))
                .as("kotlinc compiled the Kotlin source while ignoring module-info.java")
                .isNotEmptyFile();
        assertThat(classes.resolve("module-info.class"))
                .as("kotlinc must not parse or emit module-info.java; javac owns it")
                .doesNotExist();
    }

    @Test
    public void picks_up_release_from_upstream_javac_properties() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("kotlinc/kotlinc/maven/org.jetbrains.kotlin/kotlin-compiler-embeddable", KOTLIN_VERSION);
        properties.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.kt"), """
                package sample
                class Sample
                """);
        Path processFolder = Files.createDirectories(project.resolve("process"));
        SequencedProperties javacProperties = new SequencedProperties();
        javacProperties.setProperty("--release", "11");
        javacProperties.store(processFolder.resolve("javac.properties"));

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "kotlin",
                new KotlinCompilerModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path classes = root
                .resolve("kotlin")
                .resolve(KotlinCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        Path sampleClass = classes.resolve("sample/Sample.class");
        assertThat(sampleClass).isNotEmptyFile();
        byte[] bytes = Files.readAllBytes(sampleClass);
        int majorVersion = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        assertThat(majorVersion)
                .as("--release=11 propagated to -jvm-target so the .class file carries major version 55 (Java 11)")
                .isEqualTo(55);
    }

    @Test
    public void kotlin_can_reference_java_sources_supplied_to_the_same_step() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("kotlinc/kotlinc/maven/org.jetbrains.kotlin/kotlin-compiler-embeddable", KOTLIN_VERSION);
        properties.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Greeter.java"), """
                package sample;
                public class Greeter {
                    public String hello() { return "Hello"; }
                }
                """);
        Files.writeString(sampleDir.resolve("Sample.kt"), """
                package sample
                class Sample {
                    fun greet(): String = Greeter().hello() + " from Kotlin"
                }
                """);

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "kotlin",
                new KotlinCompilerModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path classes = root
                .resolve("kotlin")
                .resolve(KotlinCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(classes.resolve("sample/Sample.class"))
                .as("Sample.kt resolved the Greeter type from the .java source companion and compiled")
                .isNotEmptyFile();
    }

    @Test
    public void downloads_the_full_compiler_dependency_set_from_the_kotlin_pom() throws IOException {
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("kotlinc/kotlinc/maven/org.jetbrains.kotlin/kotlin-compiler-embeddable", KOTLIN_VERSION);
        properties.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.kt"), "package sample\nclass Sample\n");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "kotlin",
                new KotlinCompilerModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path artifacts = root
                .resolve("kotlin")
                .resolve("dependencies")
                .resolve("output");
        List<String> names = listJarNames(artifacts);
        assertThat(names).anyMatch(name -> name.contains("kotlin-compiler-embeddable"));
        assertThat(names).anyMatch(name -> name.contains("kotlin-stdlib"));
        assertThat(names).anyMatch(name -> name.contains("kotlinx-coroutines-core"));
        assertThat(names).anyMatch(name -> name.contains("annotations"));
    }

    @Test
    public void requires_step_picks_module_coordinate_when_module_resolver_available() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "kotlin",
                new KotlinCompilerModule(
                        Map.of(),
                        Map.of("module", (_, _, _, _, _, _, _) -> new LinkedHashMap<>())),
                "project");
        executor.execute("kotlin/" + "required");

        Path requiredOutput = root.resolve("kotlin").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactly("kotlinc/runtime/module/kotlin.compiler.embeddable");
        assertThat(requiredOutput.resolve(BuildStep.VERSIONS)).doesNotExist();
    }

    @Test
    public void requires_step_picks_maven_coordinate_when_only_maven_resolver_available() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "kotlin",
                new KotlinCompilerModule(
                        Map.of(),
                        Map.of("maven", (_, _, _, _, _, _, _) -> new LinkedHashMap<>())),
                "project");
        executor.execute("kotlin/" + "required");

        Path requiredOutput = root.resolve("kotlin").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .containsExactly("kotlinc/runtime/maven/org.jetbrains.kotlin/kotlin-compiler-embeddable/RELEASE");
        assertThat(requiredOutput.resolve(BuildStep.VERSIONS)).doesNotExist();
    }

    @Test
    public void requires_step_prefers_maven_over_module_when_both_available() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "kotlin",
                new KotlinCompilerModule(
                        Map.of(),
                        Map.of(
                                "module", (_, _, _, _, _, _, _) -> new LinkedHashMap<>(),
                                "maven", (_, _, _, _, _, _, _) -> new LinkedHashMap<>())),
                "project");
        executor.execute("kotlin/" + "required");

        Path requiredOutput = root.resolve("kotlin").resolve("required").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(requiredOutput.resolve(BuildStep.REQUIRES));
        assertThat(requires.stringPropertyNames())
                .singleElement()
                .satisfies(name -> assertThat(name).startsWith("kotlinc/runtime/maven/"));
    }

    @Test
    public void requires_step_fails_when_no_supported_prefix_is_registered() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "kotlin",
                new KotlinCompilerModule(
                        Map.of(),
                        Map.of("vendor", (_, _, _, _, _, _, _) -> new LinkedHashMap<>())),
                "project");

        assertThatThrownBy(() -> executor.execute("kotlin/" + "required"))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("No suitable resolver");
    }

    @Test
    public void group_emits_an_independent_resolution_trail() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "kotlin",
                new KotlinCompilerModule(Map.of("maven", files()), Map.of("maven", Resolver.identity()))
                        .group("custom"),
                "project");
        executor.execute("kotlin/dependencies");

        Path resolvedOutput = root.resolve("kotlin").resolve("dependencies").resolve("output");
        SequencedProperties requires = SequencedProperties.ofFiles(resolvedOutput.resolve(BuildStep.DEPENDENCIES));
        assertThat(requires.stringPropertyNames())
                .containsExactly("custom/runtime/maven/org.jetbrains.kotlin/kotlin-compiler-embeddable/RELEASE");
    }

    @Test
    public void exposes_resolved_alongside_classes_so_coordinates_reach_the_pin_stage() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "kotlin",
                new KotlinCompilerModule(
                        Map.of(),
                        Map.of("maven", (_, _, _, _, _, _, _) -> new LinkedHashMap<>())),
                "project");
        SequencedMap<String, Path> outputs = executor.execute();

        assertThat(outputs).containsKeys("kotlin/classes", "kotlin/artifacts");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
    }

    private Repository files() {
        return (_, coordinate) -> {
            Path file = Files.write(
                    Files.createDirectories(root.resolve("served")).resolve(coordinate.replace('/', '-') + ".jar"),
                    coordinate.getBytes(StandardCharsets.UTF_8));
            return Optional.of(RepositoryItem.ofFile(file));
        };
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
        List<URL> urls = new ArrayList<>();
        for (Path jar : Dependencies.all(folder)) {
            urls.add(jar.toUri().toURL());
        }
        return urls;
    }

    private static List<String> listJarNames(Path folder) throws IOException {
        List<String> names = new ArrayList<>();
        for (Path jar : Dependencies.all(folder)) {
            names.add(jar.getFileName().toString());
        }
        return names;
    }
}
