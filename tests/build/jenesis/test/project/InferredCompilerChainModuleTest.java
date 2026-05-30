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
import build.jenesis.project.InferredCompilerChainModule;
import build.jenesis.project.KotlinCompilerModule;
import build.jenesis.project.ScalaCompilerModule;

import static org.assertj.core.api.Assertions.assertThat;

public class InferredCompilerChainModuleTest {

    private static final String KOTLIN_VERSION = "2.2.0";
    private static final String SCALA_VERSION = "3.5.2";

    @TempDir
    private Path root, project;

    @Test
    public void java_only_project_runs_only_javac_and_skips_kotlin_and_scala() throws IOException {
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("OnlyJava.java"), """
                package sample;
                public class OnlyJava {
                    public String greet() { return "Hello from Java"; }
                }
                """);

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "chain",
                new InferredCompilerChainModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path javaClasses = root
                .resolve("chain")
                .resolve(InferredCompilerChainModule.COMPILE)
                .resolve(InferredCompilerChainModule.JAVA)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(javaClasses.resolve("sample/OnlyJava.class")).isNotEmptyFile();
    }

    @Test
    public void chain_compiles_java_then_kotlin_then_scala_when_all_three_languages_present() throws Exception {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("maven@kotlin/org.jetbrains.kotlin/kotlin-compiler-embeddable", KOTLIN_VERSION);
        versions.setProperty("maven@scala/org.scala-lang/scala3-compiler_3", SCALA_VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Base.java"), """
                package sample;
                public class Base {
                    public String name() { return "base"; }
                }
                """);
        Files.writeString(sampleDir.resolve("Mid.kt"), """
                package sample
                class Mid {
                    fun describe(): String = Base().name() + "->kotlin"
                }
                """);
        Files.writeString(sampleDir.resolve("Top.scala"), """
                package sample
                class Top:
                  def describe(): String = Mid().describe() + "->scala"
                """);

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "chain",
                new InferredCompilerChainModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path javaClasses = root
                .resolve("chain")
                .resolve(InferredCompilerChainModule.COMPILE)
                .resolve(InferredCompilerChainModule.JAVA)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(javaClasses.resolve("sample/Base.class"))
                .as("Javac ran first and emitted Base.class")
                .isNotEmptyFile();
        Path kotlinClasses = root
                .resolve("chain")
                .resolve(InferredCompilerChainModule.COMPILE)
                .resolve(InferredCompilerChainModule.KOTLIN)
                .resolve(KotlinCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(kotlinClasses.resolve("sample/Mid.class"))
                .as("Kotlin module ran second and emitted Mid.class against Java's output")
                .isNotEmptyFile();
        Path scalaClasses = root
                .resolve("chain")
                .resolve(InferredCompilerChainModule.COMPILE)
                .resolve(InferredCompilerChainModule.SCALA)
                .resolve(ScalaCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(scalaClasses.resolve("sample/Top.class"))
                .as("Scala module ran last and emitted Top.class against Kotlin+Java output")
                .isNotEmptyFile();

        Path kotlinArtifacts = root
                .resolve("chain")
                .resolve(InferredCompilerChainModule.COMPILE)
                .resolve(InferredCompilerChainModule.KOTLIN)
                .resolve(KotlinCompilerModule.ARTIFACTS)
                .resolve("output")
                .resolve(BuildStep.DEPENDENCIES);
        Path scalaArtifacts = root
                .resolve("chain")
                .resolve(InferredCompilerChainModule.COMPILE)
                .resolve(InferredCompilerChainModule.SCALA)
                .resolve(ScalaCompilerModule.ARTIFACTS)
                .resolve("output")
                .resolve(BuildStep.DEPENDENCIES);
        URL[] urls = Stream.concat(
                        Stream.of(scalaClasses.toUri().toURL()),
                        Stream.concat(collectJarUrls(kotlinArtifacts).stream(), collectJarUrls(scalaArtifacts).stream()))
                .toArray(URL[]::new);
        URLClassLoader loader = new URLClassLoader(urls, null);
        Class<?> top = loader.loadClass("sample.Top");
        Object instance = top.getConstructor().newInstance();
        Object description = top.getMethod("describe").invoke(instance);
        assertThat(description).isEqualTo("base->kotlin->scala");
    }

    @Test
    public void kotlin_only_project_runs_kotlin_skipping_scala() throws Exception {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("maven@kotlin/org.jetbrains.kotlin/kotlin-compiler-embeddable", KOTLIN_VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.kt"), """
                package sample
                class Sample {
                    fun greet(): String = "Hello from Kotlin"
                }
                """);

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "chain",
                new InferredCompilerChainModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path kotlinClasses = root
                .resolve("chain")
                .resolve(InferredCompilerChainModule.COMPILE)
                .resolve(InferredCompilerChainModule.KOTLIN)
                .resolve(KotlinCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(kotlinClasses.resolve("sample/Sample.class")).isNotEmptyFile();
        assertThat(root.resolve("chain").resolve(InferredCompilerChainModule.COMPILE).resolve(InferredCompilerChainModule.SCALA))
                .as("Scala module was not wired because no .scala sources were detected")
                .doesNotExist();
    }

    @Test
    public void resource_step_copies_resources_when_multiple_compilers_are_wired() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("maven@kotlin/org.jetbrains.kotlin/kotlin-compiler-embeddable", KOTLIN_VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Base.java"), "package sample; public class Base { }\n");
        Files.writeString(sampleDir.resolve("Mid.kt"), "package sample\nclass Mid\n");
        Files.writeString(sampleDir.resolve("app.properties"), "key=value");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "chain",
                new InferredCompilerChainModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path resourceOutput = root
                .resolve("chain")
                .resolve(InferredCompilerChainModule.COMPILE)
                .resolve(InferredCompilerChainModule.RESOURCE)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(resourceOutput.resolve("sample/app.properties")).content().isEqualTo("key=value");
        Path javaClasses = root
                .resolve("chain")
                .resolve(InferredCompilerChainModule.COMPILE)
                .resolve(InferredCompilerChainModule.JAVA)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(javaClasses.resolve("sample/app.properties"))
                .as("with multiple compilers, Javac is forced to includeResources(false) so resources are not duplicated")
                .doesNotExist();
    }

    @Test
    public void resource_step_is_not_wired_when_a_single_compiler_is_present() throws IOException {
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("OnlyJava.java"), "package sample; public class OnlyJava { }\n");
        Files.writeString(sampleDir.resolve("app.properties"), "key=value");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "chain",
                new InferredCompilerChainModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        assertThat(root.resolve("chain").resolve(InferredCompilerChainModule.COMPILE).resolve(InferredCompilerChainModule.RESOURCE))
                .as("with a single compiler the resource step is not wired; Javac copies resources itself")
                .doesNotExist();
        Path javaClasses = root
                .resolve("chain")
                .resolve(InferredCompilerChainModule.COMPILE)
                .resolve(InferredCompilerChainModule.JAVA)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(javaClasses.resolve("sample/app.properties")).content().isEqualTo("key=value");
    }

    @Test
    public void resource_step_copies_resources_when_no_compilers_are_wired() throws IOException {
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("app.properties"), "key=value");

        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "chain",
                new InferredCompilerChainModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();

        Path resourceOutput = root
                .resolve("chain")
                .resolve(InferredCompilerChainModule.COMPILE)
                .resolve(InferredCompilerChainModule.RESOURCE)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(resourceOutput.resolve("sample/app.properties")).content().isEqualTo("key=value");
    }

    @Test
    public void scala_only_project_runs_scala_and_resource_only() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("maven@scala/org.scala-lang/scala3-compiler_3", SCALA_VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Sample.scala"), """
                package sample
                class Sample:
                  def greet(): String = "Hello"
                """);
        Files.writeString(sampleDir.resolve("app.properties"), "key=value");

        runChain();

        Path scalaClasses = chainCompile()
                .resolve(InferredCompilerChainModule.SCALA)
                .resolve(ScalaCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(scalaClasses.resolve("sample/Sample.class")).isNotEmptyFile();
        assertThat(scalaClasses.resolve("sample/app.properties"))
                .as("single Scala compiler copies the resource into its Versions output")
                .content().isEqualTo("key=value");
        assertThat(chainCompile().resolve(InferredCompilerChainModule.RESOURCE))
                .as("resource step is not wired when a single compiler is present")
                .doesNotExist();
        assertThat(chainCompile().resolve(InferredCompilerChainModule.JAVA)).doesNotExist();
        assertThat(chainCompile().resolve(InferredCompilerChainModule.KOTLIN)).doesNotExist();
        assertNoSourceFilesInClassOutputs();
    }

    @Test
    public void java_and_scala_only_routes_resource_through_dedicated_step() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("maven@scala/org.scala-lang/scala3-compiler_3", SCALA_VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Base.java"), "package sample; public class Base { }\n");
        Files.writeString(sampleDir.resolve("Sample.scala"), """
                package sample
                class Sample(b: Base)
                """);
        Files.writeString(sampleDir.resolve("app.properties"), "key=value");

        runChain();

        Path resourceClasses = chainCompile()
                .resolve(InferredCompilerChainModule.RESOURCE)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(resourceClasses.resolve("sample/app.properties")).content().isEqualTo("key=value");
        Path javaClasses = chainCompile()
                .resolve(InferredCompilerChainModule.JAVA)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(javaClasses.resolve("sample/app.properties"))
                .as("Javac suppressed resources because Scala is also wired")
                .doesNotExist();
        Path scalaCompileOnly = chainCompile()
                .resolve(InferredCompilerChainModule.SCALA)
                .resolve("compiled")
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(scalaCompileOnly.resolve("sample/app.properties"))
                .as("Scala compile step suppressed resources because Java is also wired")
                .doesNotExist();
        assertThat(chainCompile().resolve(InferredCompilerChainModule.KOTLIN)).doesNotExist();
        assertNoSourceFilesInClassOutputs();
    }

    @Test
    public void kotlin_and_scala_only_routes_resource_through_dedicated_step() throws IOException {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("maven@kotlin/org.jetbrains.kotlin/kotlin-compiler-embeddable", KOTLIN_VERSION);
        versions.setProperty("maven@scala/org.scala-lang/scala3-compiler_3", SCALA_VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Mid.kt"), "package sample\nclass Mid\n");
        Files.writeString(sampleDir.resolve("Sample.scala"), """
                package sample
                class Sample
                """);
        Files.writeString(sampleDir.resolve("app.properties"), "key=value");

        runChain();

        Path resourceClasses = chainCompile()
                .resolve(InferredCompilerChainModule.RESOURCE)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(resourceClasses.resolve("sample/app.properties")).content().isEqualTo("key=value");
        Path kotlinCompileOnly = chainCompile()
                .resolve(InferredCompilerChainModule.KOTLIN)
                .resolve("compiled")
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(kotlinCompileOnly.resolve("sample/app.properties"))
                .as("Kotlin compile step suppressed resources because Scala is also wired")
                .doesNotExist();
        Path scalaCompileOnly = chainCompile()
                .resolve(InferredCompilerChainModule.SCALA)
                .resolve("compiled")
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(scalaCompileOnly.resolve("sample/app.properties"))
                .as("Scala compile step suppressed resources because Kotlin is also wired")
                .doesNotExist();
        assertThat(chainCompile().resolve(InferredCompilerChainModule.JAVA)).doesNotExist();
        assertNoSourceFilesInClassOutputs();
    }

    @Test
    public void all_three_compilers_route_resource_through_dedicated_step() throws Exception {
        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("maven@kotlin/org.jetbrains.kotlin/kotlin-compiler-embeddable", KOTLIN_VERSION);
        versions.setProperty("maven@scala/org.scala-lang/scala3-compiler_3", SCALA_VERSION);
        versions.store(project.resolve(BuildStep.VERSIONS));
        Path sampleDir = Files.createDirectories(project.resolve(BuildStep.SOURCES + "sample"));
        Files.writeString(sampleDir.resolve("Base.java"), "package sample; public class Base { }\n");
        Files.writeString(sampleDir.resolve("Mid.kt"), "package sample\nclass Mid\n");
        Files.writeString(sampleDir.resolve("Top.scala"), "package sample\nclass Top\n");
        Files.writeString(sampleDir.resolve("app.properties"), "key=value");

        runChain();

        Path resourceClasses = chainCompile()
                .resolve(InferredCompilerChainModule.RESOURCE)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(resourceClasses.resolve("sample/app.properties")).content().isEqualTo("key=value");
        Path javaClasses = chainCompile()
                .resolve(InferredCompilerChainModule.JAVA)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(javaClasses.resolve("sample/app.properties"))
                .as("Javac suppressed resources because Kotlin and Scala are also wired")
                .doesNotExist();
        Path kotlinCompileOnly = chainCompile()
                .resolve(InferredCompilerChainModule.KOTLIN)
                .resolve("compiled")
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(kotlinCompileOnly.resolve("sample/app.properties"))
                .as("Kotlin compile step suppressed resources because other compilers are wired")
                .doesNotExist();
        Path scalaCompileOnly = chainCompile()
                .resolve(InferredCompilerChainModule.SCALA)
                .resolve("compiled")
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(scalaCompileOnly.resolve("sample/app.properties"))
                .as("Scala compile step suppressed resources because other compilers are wired")
                .doesNotExist();
        assertNoSourceFilesInClassOutputs();
    }

    private void runChain() throws IOException {
        BuildExecutor executor = newExecutor();
        executor.addSource("project", project);
        executor.addModule(
                "chain",
                new InferredCompilerChainModule(
                        Map.of("maven", mavenCentral()),
                        Map.of("maven", new MavenPomResolver())),
                "project");
        executor.execute();
    }

    private Path chainCompile() {
        return root.resolve("chain").resolve(InferredCompilerChainModule.COMPILE);
    }

    private void assertNoSourceFilesInClassOutputs() throws IOException {
        Path compileRoot = chainCompile();
        if (!Files.exists(compileRoot)) {
            return;
        }
        List<Path> leakedSources = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(compileRoot)) {
            for (Path candidate : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                String name = candidate.getFileName().toString();
                if (!name.endsWith(".java") && !name.endsWith(".kt") && !name.endsWith(".scala")) {
                    continue;
                }
                if (isUnderOutputClasses(candidate)) {
                    leakedSources.add(candidate);
                }
            }
        }
        assertThat(leakedSources)
                .as("No language source files should appear in any compile output's classes/ folder")
                .isEmpty();
    }

    private static boolean isUnderOutputClasses(Path file) {
        Path cursor = file.getParent();
        while (cursor != null) {
            if (cursor.getFileName() != null && cursor.getFileName().toString().equals("classes")) {
                Path classesParent = cursor.getParent();
                if (classesParent != null
                        && classesParent.getFileName() != null
                        && classesParent.getFileName().toString().equals("output")) {
                    return true;
                }
            }
            cursor = cursor.getParent();
        }
        return false;
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
}
