package build.jenesis.test.maven;

import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenDefaultVersionNegotiator;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenProject;
import build.jenesis.maven.MavenRepository;
import build.jenesis.project.MultiProjectModule;
import build.jenesis.project.JavaModule;

import module java.base;
import module org.junit.jupiter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

public class MavenProjectTest {

    @TempDir
    private Path project, build, repository;
    private MavenRepository mavenRepository;
    private MavenPomResolver mavenPomResolver;

    @BeforeEach
    public void setUp() throws Exception {
        mavenRepository = new MavenDefaultRepository(repository.toUri(),
                null,
                Map.of());
        mavenPomResolver = new MavenPomResolver(MavenDefaultVersionNegotiator.maven());
    }

    @Test
    public void can_resolve_pom() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>artifact</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("src/main/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("src/test/java")).resolve("source"), "bar");
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module/module-/manifests", "maven/module/test-module-/manifests");
        Path module = results.get("maven/module/module-/manifests");
        assertThat(module.resolve(BuildStep.IDENTITY)).exists();
        Properties coordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(module.resolve(BuildStep.IDENTITY))) {
            coordinates.load(reader);
        }
        assertThat(coordinates).containsOnlyKeys(
                "maven/group/artifact/jar/1",
                "maven/group/artifact/pom/1");
        assertThat(coordinates.getProperty("maven/group/artifact/jar/1")).isEmpty();
        Path moduleRequires = module.resolve(MultiProjectModule.COMPILE).resolve(BuildStep.REQUIRES);
        assertThat(moduleRequires).exists();
        Properties dependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(moduleRequires)) {
            dependencies.load(reader);
        }
        assertThat(dependencies).containsOnlyKeys("maven/other/artifact/jar/1");
        assertThat(dependencies.getProperty("maven/other/artifact/jar/1")).isEmpty();
        Path testModule = results.get("maven/module/test-module-/manifests");
        assertThat(testModule.resolve(BuildStep.IDENTITY)).exists();
        Properties testCoordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(testModule.resolve(BuildStep.IDENTITY))) {
            testCoordinates.load(reader);
        }
        assertThat(testCoordinates).containsOnlyKeys(
                "maven/group/artifact/jar/tests/1",
                "maven/group/artifact/pom/1");
        assertThat(testCoordinates.getProperty("maven/group/artifact/jar/tests/1")).isEmpty();
        Path testModuleRequires = testModule.resolve(MultiProjectModule.COMPILE).resolve(BuildStep.REQUIRES);
        assertThat(testModuleRequires).exists();
        Properties testDependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(testModuleRequires)) {
            testDependencies.load(reader);
        }
        assertThat(testDependencies).containsOnlyKeys("maven/group/artifact/jar/1");
        assertThat(testDependencies.getProperty("maven/group/artifact/jar/1")).isEmpty();
    }

    @Test
    public void can_resolve_multi_pom() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>parent</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <modules>
                      <module>subproject</module>
                    </modules>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("src/main/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("src/test/java")).resolve("source"), "bar");
        Path subproject = Files.createDirectory(project.resolve("subproject"));
        Files.writeString(subproject.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                        <parent>
                        <groupId>parent</groupId>
                        <artifactId>artifact</artifactId>
                        <version>1</version>
                    </parent>
                </project>
                """);
        Files.writeString(Files.createDirectories(subproject.resolve("src/main/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(subproject.resolve("src/test/java")).resolve("source"), "bar");
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module/module-/manifests", "maven/module/module-subproject/manifests");
        Path parent = results.get("maven/module/module-/manifests");
        assertThat(parent.resolve(BuildStep.IDENTITY)).exists();
        Properties parentCoordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(parent.resolve(BuildStep.IDENTITY))) {
            parentCoordinates.load(reader);
        }
        assertThat(parentCoordinates).containsOnlyKeys(
                "maven/parent/artifact/jar/1",
                "maven/parent/artifact/pom/1");
        assertThat(parentCoordinates.getProperty("maven/parent/artifact/jar/1")).isEmpty();
        assertThat(parent.resolve(MultiProjectModule.COMPILE).resolve(BuildStep.REQUIRES)).exists().content().isEmpty();
        Path parentTests = results.get("maven/module/test-module-/manifests");
        assertThat(parentTests.resolve(BuildStep.IDENTITY)).exists();
        Properties parentTestCoordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(parentTests.resolve(BuildStep.IDENTITY))) {
            parentTestCoordinates.load(reader);
        }
        assertThat(parentTestCoordinates).containsOnlyKeys(
                "maven/parent/artifact/jar/tests/1",
                "maven/parent/artifact/pom/1");
        assertThat(parentTestCoordinates.getProperty("maven/parent/artifact/jar/tests/1")).isEmpty();
        Properties parentTestDependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(parentTests.resolve(MultiProjectModule.COMPILE).resolve(BuildStep.REQUIRES))) {
            parentTestDependencies.load(reader);
        }
        assertThat(parentTestDependencies).containsOnlyKeys("maven/parent/artifact/jar/1");
        assertThat(parentTestDependencies.getProperty("maven/parent/artifact/jar/1")).isEmpty();
        Path child = results.get("maven/module/module-subproject/manifests");
        assertThat(child.resolve(BuildStep.IDENTITY)).exists();
        Properties childCoordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(child.resolve(BuildStep.IDENTITY))) {
            childCoordinates.load(reader);
        }
        assertThat(childCoordinates).containsOnlyKeys(
                "maven/group/artifact/jar/1",
                "maven/group/artifact/pom/1");
        assertThat(childCoordinates.getProperty("maven/group/artifact/jar/1")).isEmpty();
        assertThat(child.resolve(MultiProjectModule.COMPILE).resolve(BuildStep.REQUIRES)).exists().content().isEmpty();
        Path childTests = results.get("maven/module/test-module-subproject/manifests");
        assertThat(childTests.resolve(BuildStep.IDENTITY)).exists();
        Properties childTestCoordinates = new Properties();
        try (Reader reader = Files.newBufferedReader(childTests.resolve(BuildStep.IDENTITY))) {
            childTestCoordinates.load(reader);
        }
        assertThat(childTestCoordinates).containsOnlyKeys(
                "maven/group/artifact/jar/tests/1",
                "maven/group/artifact/pom/1");
        assertThat(childTestCoordinates.getProperty("maven/group/artifact/jar/tests/1")).isEmpty();
        Properties childTestDependencies = new Properties();
        try (Reader reader = Files.newBufferedReader(childTests.resolve(MultiProjectModule.COMPILE).resolve(BuildStep.REQUIRES))) {
            childTestDependencies.load(reader);
        }
        assertThat(childTestDependencies).containsOnlyKeys("maven/group/artifact/jar/1");
        assertThat(childTestDependencies.getProperty("maven/group/artifact/jar/1")).isEmpty();
    }

    @Test
    public void can_resolve_sources_and_resources() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("src/main/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("src/main/resources")).resolve("resource"), "bar");
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module/module-/manifests",
                "maven/module/module-/sources",
                "maven/module/module-/resources-1");
        assertThat(results.get("maven/module/module-/sources").resolve(BuildStep.SOURCES + "source")).content().isEqualTo("foo");
        assertThat(results.get("maven/module/module-/resources-1").resolve(BuildStep.RESOURCES + "resource")).content().isEqualTo("bar");
    }

    @Test
    public void can_resolve_sources_and_resources_explicit() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <build>
                       <sourceDirectory>sources</sourceDirectory>
                       <resources>
                         <resource>resources-1</resource>
                         <resource>resources-2</resource>
                       </resources>
                    </build>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("sources")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("resources-1")).resolve("resource1"), "bar");
        Files.writeString(Files.createDirectories(project.resolve("resources-2")).resolve("resource2"), "qux");
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module/module-/manifests",
                "maven/module/module-/sources",
                "maven/module/module-/resources-1",
                "maven/module/module-/resources-2");
        assertThat(results.get("maven/module/module-/sources").resolve(BuildStep.SOURCES + "source")).content().isEqualTo("foo");
        assertThat(results.get("maven/module/module-/resources-1").resolve(BuildStep.RESOURCES + "resource1")).content().isEqualTo("bar");
        assertThat(results.get("maven/module/module-/resources-2").resolve(BuildStep.RESOURCES + "resource2")).content().isEqualTo("qux");
    }

    @Test
    public void can_resolve_test_sources_and_resources() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("src/test/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("src/test/resources")).resolve("resource"), "bar");
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module/test-module-/manifests",
                "maven/module/test-module-/sources",
                "maven/module/test-module-/resources-1");
        assertThat(results.get("maven/module/test-module-/sources").resolve(BuildStep.SOURCES + "source")).content().isEqualTo("foo");
        assertThat(results.get("maven/module/test-module-/resources-1").resolve(BuildStep.RESOURCES + "resource")).content().isEqualTo("bar");
    }

    @Test
    public void can_resolve_test_sources_and_resources_explicit() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <build>
                       <testSourceDirectory>sources</testSourceDirectory>
                       <testResources>
                         <testResource>resources-1</testResource>
                         <testResource>resources-2</testResource>
                       </testResources>
                    </build>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("sources")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("resources-1")).resolve("resource1"), "bar");
        Files.writeString(Files.createDirectories(project.resolve("resources-2")).resolve("resource2"), "qux");
        BuildExecutor executor = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module/test-module-/manifests",
                "maven/module/test-module-/sources",
                "maven/module/test-module-/resources-1",
                "maven/module/test-module-/resources-2");
        assertThat(results.get("maven/module/test-module-/sources").resolve(BuildStep.SOURCES + "source")).content().isEqualTo("foo");
        assertThat(results.get("maven/module/test-module-/resources-1").resolve(BuildStep.RESOURCES + "resource1")).content().isEqualTo("bar");
        assertThat(results.get("maven/module/test-module-/resources-2").resolve(BuildStep.RESOURCES + "resource2")).content().isEqualTo("qux");
    }

    @Test
    public void can_resolve_multi_module_project() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>parent</artifactId>
                    <version>1</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>foo</module>
                        <module>bar</module>
                    </modules>
                </project>
                """);
        Files.writeString(Files.createDirectory(project.resolve("foo")).resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>group</groupId>
                        <artifactId>parent</artifactId>
                        <version>1</version>
                    </parent>
                    <artifactId>foo</artifactId>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("foo/src/main/java/foo")).resolve("Foo.java"), """
                package foo;
                public class Foo { }
                """);
        Files.writeString(Files.createDirectory(project.resolve("bar")).resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>group</groupId>
                        <artifactId>parent</artifactId>
                        <version>1</version>
                    </parent>
                    <artifactId>bar</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>group</groupId>
                            <artifactId>foo</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("bar/src/main/java/bar")).resolve("Bar.java"), """
                package bar;
                import foo.Foo;
                public class Bar extends Foo { }
                """);
        BuildExecutor root = BuildExecutor.of(build,
                new HashDigestFunction("MD5"),
                BuildExecutorCallback.nop());
        root.addModule("maven", MavenProject.make(project,
                "maven",
                "SHA256",
                new MavenDefaultRepository(repository.toUri(), null, Map.of()),
                new MavenPomResolver(),
                descriptor -> {
                    switch (descriptor.name()) {
                        case "module-foo" -> assertThat(descriptor.dependencies()).isEmpty();
                        case "module-bar" -> assertThat(descriptor.dependencies()).containsExactly("module-foo");
                        default -> fail("Unexpected module: " + descriptor.name());
                    }
                    return (buildExecutor, inherited) -> {
                        switch (descriptor.name()) {
                            case "module-foo" -> assertThat(inherited).containsOnlyKeys(
                                    "../sources",
                                    "../manifests",
                                    "../compile/dependencies/checked",
                                    "../compile/dependencies/artifacts",
                                    "../runtime/dependencies/checked",
                                    "../runtime/dependencies/artifacts");
                            case "module-bar" -> assertThat(inherited).containsOnlyKeys(
                                    "../sources",
                                    "../manifests",
                                    "../compile/dependencies/checked",
                                    "../compile/dependencies/artifacts",
                                    "../runtime/dependencies/checked",
                                    "../runtime/dependencies/artifacts",
                                    "../../module-foo/compile/prepare",
                                    "../../module-foo/compile/dependencies/resolved",
                                    "../../module-foo/compile/dependencies/checked",
                                    "../../module-foo/compile/dependencies/artifacts",
                                    "../../module-foo/runtime/prepare",
                                    "../../module-foo/runtime/dependencies/resolved",
                                    "../../module-foo/runtime/dependencies/checked",
                                    "../../module-foo/runtime/dependencies/artifacts",
                                    "../../module-foo/produce/java/classes",
                                    "../../module-foo/produce/java/artifacts",
                                    "../../module-foo/assign");
                            default -> fail("Unexpected module: " + descriptor.name());
                        }
                        buildExecutor.addModule("java", new JavaModule(),
                                "../sources", "../manifests",
                                "../compile/dependencies/artifacts",
                                "../runtime/dependencies/artifacts");
                    };
                }));
        SequencedMap<String, Path> results = root.execute(Runnable::run).toCompletableFuture().join();
        Properties foo = new SequencedProperties();
        try (Reader reader = Files.newBufferedReader(results
                .get("maven/build/module/module-foo/assign")
                .resolve(BuildStep.IDENTITY))) {
            foo.load(reader);
        }
        assertThat(foo.stringPropertyNames()).containsExactly("maven/group/foo/jar/1", "maven/group/foo/pom/1");
        assertThat(foo.getProperty("maven/group/foo/jar/1"))
                .isEqualTo("../../produce/java/artifacts/output/artifacts/classes.jar");
        assertThat(foo.getProperty("maven/group/foo/pom/1"))
                .isEqualTo("../../../../../identifier/scan/output/pom/foo/pom.xml");
        Properties bar = new SequencedProperties();
        try (Reader reader = Files.newBufferedReader(results
                .get("maven/build/module/module-bar/assign")
                .resolve(BuildStep.IDENTITY))) {
            bar.load(reader);
        }
        assertThat(bar.stringPropertyNames()).containsExactly("maven/group/bar/jar/1", "maven/group/bar/pom/1");
        assertThat(bar.getProperty("maven/group/bar/jar/1"))
                .isEqualTo("../../produce/java/artifacts/output/artifacts/classes.jar");
        assertThat(bar.getProperty("maven/group/bar/pom/1"))
                .isEqualTo("../../../../../identifier/scan/output/pom/bar/pom.xml");
    }

    @Test
    public void artifactsByModule_links_classes_jar_and_pom_xml_under_sub_module_folder() {
        Function<Path, Optional<Path>> placement = MavenProject.artifactsByModule();
        Path jar = Path.of("/wrap/build/module/test-module-foo/produce/java/artifacts/output/artifacts/classes.jar");
        Path pom = Path.of("/wrap/build/module/test-module-foo/build/pom/output/pom.xml");
        Path other = Path.of("/wrap/build/module/test-module-foo/build/java/classes/output/A.class");
        assertThat(placement.apply(jar)).contains(Path.of("test-module-foo", "classes.jar"));
        assertThat(placement.apply(pom)).contains(Path.of("test-module-foo", "pom.xml"));
        assertThat(placement.apply(other)).isEmpty();
    }
}
