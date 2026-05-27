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
        SequencedProperties kotlinProperties = new SequencedProperties();
        kotlinProperties.setProperty("version", KOTLIN_VERSION);
        kotlinProperties.store(project.resolve("kotlin.properties"));
        SequencedProperties scalaProperties = new SequencedProperties();
        scalaProperties.setProperty("version", SCALA_VERSION);
        scalaProperties.store(project.resolve("scala.properties"));
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
                .resolve(InferredCompilerChainModule.JAVA)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(javaClasses.resolve("sample/OnlyJava.class")).isNotEmptyFile();
    }

    @Test
    public void chain_compiles_java_then_kotlin_then_scala_when_all_three_languages_present() throws Exception {
        SequencedProperties kotlinProperties = new SequencedProperties();
        kotlinProperties.setProperty("version", KOTLIN_VERSION);
        kotlinProperties.store(project.resolve("kotlin.properties"));
        SequencedProperties scalaProperties = new SequencedProperties();
        scalaProperties.setProperty("version", SCALA_VERSION);
        scalaProperties.store(project.resolve("scala.properties"));
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
                .resolve(InferredCompilerChainModule.JAVA)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(javaClasses.resolve("sample/Base.class"))
                .as("Javac ran first and emitted Base.class")
                .isNotEmptyFile();
        Path kotlinClasses = root
                .resolve("chain")
                .resolve(InferredCompilerChainModule.KOTLIN)
                .resolve(KotlinCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(kotlinClasses.resolve("sample/Mid.class"))
                .as("Kotlin module ran second and emitted Mid.class against Java's output")
                .isNotEmptyFile();
        Path scalaClasses = root
                .resolve("chain")
                .resolve(InferredCompilerChainModule.SCALA)
                .resolve(ScalaCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(scalaClasses.resolve("sample/Top.class"))
                .as("Scala module ran last and emitted Top.class against Kotlin+Java output")
                .isNotEmptyFile();

        Path kotlinArtifacts = root
                .resolve("chain")
                .resolve(InferredCompilerChainModule.KOTLIN)
                .resolve(KotlinCompilerModule.ARTIFACTS)
                .resolve("output")
                .resolve(BuildStep.DEPENDENCIES);
        Path scalaArtifacts = root
                .resolve("chain")
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
        SequencedProperties kotlinProperties = new SequencedProperties();
        kotlinProperties.setProperty("version", KOTLIN_VERSION);
        kotlinProperties.store(project.resolve("kotlin.properties"));
        SequencedProperties scalaProperties = new SequencedProperties();
        scalaProperties.setProperty("version", SCALA_VERSION);
        scalaProperties.store(project.resolve("scala.properties"));
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
                .resolve(InferredCompilerChainModule.KOTLIN)
                .resolve(KotlinCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(kotlinClasses.resolve("sample/Sample.class")).isNotEmptyFile();
        Path scalaClasses = root
                .resolve("chain")
                .resolve(InferredCompilerChainModule.SCALA)
                .resolve(ScalaCompilerModule.CLASSES)
                .resolve("output")
                .resolve(BuildStep.CLASSES);
        assertThat(scalaClasses)
                .as("Scala compile self-skipped (no .scala sources); Versions still copies through Kotlin's output")
                .isDirectory();
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
