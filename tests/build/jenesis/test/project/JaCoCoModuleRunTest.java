package build.jenesis.test.project;

import module java.base;
import module java.compiler;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCache;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.Repository;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.project.JaCoCo;
import build.jenesis.project.JaCoCoModule;
import build.jenesis.project.TestModule;
import build.jenesis.step.Javac;
import javax.tools.ToolProvider;

import static org.assertj.core.api.Assertions.assertThat;

public class JaCoCoModuleRunTest {

    private static final String VERSION = "0.8.14";

    @TempDir
    private Path root, dependencies, classes, sources;

    @Test
    public void collects_coverage_and_renders_a_report() throws Exception {
        Path artifacts = Files.createDirectory(dependencies.resolve(BuildStep.ARTIFACTS));
        for (Path path : bootModuleJars()) {
            String fileName = path.getFileName().toString();
            if (fileName.endsWith("_rt.jar") || fileName.endsWith("-rt.jar")) {
                continue;
            }
            Files.copy(path, artifacts.resolve(fileName + "-" + UUID.randomUUID() + ".jar"));
        }

        Path sourcePackage = Files.createDirectories(sources.resolve(BuildStep.SOURCES).resolve("coverage"));
        Files.writeString(sourcePackage.resolve("Covered.java"), """
                package coverage;
                public class Covered {
                    public int add(int left, int right) {
                        return left + right;
                    }
                }
                """);
        compileSources(classes.resolve(Javac.CLASSES + "coverage"), bootModuleJars(), Map.of(
                "Covered", """
                        package coverage;
                        public class Covered {
                            public int add(int left, int right) {
                                return left + right;
                            }
                        }
                        """,
                "CoveredTest", """
                        package coverage;
                        public class CoveredTest {
                            @org.junit.jupiter.api.Test
                            public void adds() {
                                if (new Covered().add(2, 3) != 5) {
                                    throw new AssertionError();
                                }
                            }
                        }
                        """));

        SequencedProperties versions = new SequencedProperties();
        versions.setProperty("main/maven/org.junit.platform/junit-platform-console",
                "1.11.4 SHA-256/a9c3309cdfded3542200de85da6cb274864439d6b02ba80bb45ecc8e0bdf1be7");
        versions.setProperty("main/maven/org.junit.platform/junit-platform-reporting",
                "1.11.4 SHA-256/df6896109bfaef4de8d2fa9e3371a6176936d1a45a6c0e7fd8f7e6dd6f4c5597");
        versions.setProperty("main/maven/org.junit.platform/junit-platform-launcher",
                "1.11.4 SHA-256/d7430bd029e7fcced53ee445e4d2d1a8a1e043ea4c4df43b6335a857f79761ae");
        versions.setProperty("main/maven/org.junit.platform/junit-platform-engine",
                "1.11.3 SHA-256/0043f72f611664735da8dc9a308bf12ecd2236b05339351c4741edb4d8fab0da");
        versions.setProperty("main/maven/org.junit.platform/junit-platform-commons",
                "1.11.3 SHA-256/be262964b0b6b48de977c61d4f931df8cf61e80e750cc3f3a0a39cdd21c1008c");
        versions.setProperty("main/maven/org.opentest4j/opentest4j",
                "1.3.0 SHA-256/48e2df636cab6563ced64dcdff8abb2355627cb236ef0bf37598682ddf742f1b");
        versions.setProperty("main/maven/org.apiguardian/apiguardian-api",
                "1.1.2 SHA-256/b509448ac506d607319f182537f0b35d71007582ec741832a1f111e5b5b70b38");
        versions.setProperty("jacoco/maven/org.jacoco/org.jacoco.agent/jar/runtime", VERSION);
        versions.setProperty("jacoco/maven/org.jacoco/org.jacoco.cli", VERSION);
        versions.store(dependencies.resolve(BuildStep.VERSIONS));

        BuildExecutor executor = newExecutor();
        executor.addSource("dependencies", dependencies);
        executor.addSource("classes", classes);
        executor.addSource("sources", sources);
        executor.addModule(
                "test",
                new TestModule(Map.of("maven", mavenCentral()), Map.of("maven", new MavenPomResolver()))
                        .observe(new JaCoCo())
                        .isTest(candidate -> candidate.endsWith("CoveredTest"))
                        .jarsOnly(false),
                "dependencies", "classes");
        executor.addModule(
                "coverage",
                new JaCoCoModule(Map.of("maven", mavenCentral()), Map.of("maven", new MavenPomResolver())),
                "test", "classes", "sources", "dependencies");
        executor.execute();

        Path exec = root.resolve("test").resolve("executed").resolve("output").resolve("jacoco.exec");
        assertThat(exec).as("the agent wrote its execution data into the test step output").isNotEmptyFile();
        assertThat(Files.readString(exec, StandardCharsets.ISO_8859_1))
                .as("the agent instrumented and recorded the covered class")
                .contains("coverage/Covered");

        Path xml = root.resolve("coverage").resolve("report").resolve("output")
                .resolve("reports").resolve("jacoco").resolve("jacoco.xml");
        assertThat(xml).as("the report processor rendered an XML report").isNotEmptyFile();
        assertThat(xml).content()
                .contains("name=\"coverage/Covered\"")
                .contains("covered=\"");
    }

    private BuildExecutor newExecutor() throws IOException {
        return BuildExecutor.of(root,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), BuildExecutorCache.nop(), false);
    }

    private static Repository mavenCentral() {
        return new MavenDefaultRepository();
    }

    private static void compileSources(Path classesDir, List<Path> classpath, Map<String, String> units)
            throws IOException {
        Files.createDirectories(classesDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            fileManager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classesDir.getParent()));
            fileManager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            List<JavaFileObject> sources = new ArrayList<>();
            for (Map.Entry<String, String> unit : units.entrySet()) {
                String body = unit.getValue();
                sources.add(new SimpleJavaFileObject(
                        URI.create("string:///coverage/" + unit.getKey() + ".java"),
                        JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return body;
                    }
                });
            }
            if (!compiler.getTask(null, fileManager, null, null, null, sources).call()) {
                throw new IllegalStateException("Failed to compile sources");
            }
        }
    }

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
