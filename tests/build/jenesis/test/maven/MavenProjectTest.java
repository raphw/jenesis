package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorCallback;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepHashFunction;
import build.jenesis.HashDigestFunction;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenDefaultVersionNegotiator;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.maven.MavenProject;
import build.jenesis.maven.MavenRepository;
import build.jenesis.project.JavaToolchainModule;

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
                Map.of(),
                _ -> {});
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
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module-/manifests",
                "maven/module-/coordinates",
                "maven/test-module-/manifests",
                "maven/test-module-/coordinates");
        Path module = results.get("maven/module-/manifests");
        assertThat(module.resolve(BuildStep.IDENTITY)).doesNotExist();
        Path moduleCoordinates = results.get("maven/module-/coordinates");
        SequencedProperties coordinates = SequencedProperties.ofFiles(moduleCoordinates.resolve(BuildStep.IDENTITY));
        assertThat(coordinates).containsOnlyKeys(
                "maven/group/artifact/1",
                "maven/group/artifact/pom/1");
        assertThat(coordinates.getProperty("maven/group/artifact/1")).isEmpty();
        Path moduleRequires = module.resolve(BuildStep.REQUIRES);
        assertThat(moduleRequires).exists();
        SequencedProperties dependencies = SequencedProperties.ofFiles(moduleRequires);
        assertThat(dependencies).containsOnlyKeys("main/compile/maven/other/artifact/1", "main/runtime/maven/other/artifact/1");
        assertThat(dependencies.getProperty("main/compile/maven/other/artifact/1")).isEmpty();
        Path testModule = results.get("maven/test-module-/manifests");
        assertThat(testModule.resolve(BuildStep.IDENTITY)).doesNotExist();
        Path testModuleCoordinates = results.get("maven/test-module-/coordinates");
        SequencedProperties testCoordinates = SequencedProperties.ofFiles(testModuleCoordinates.resolve(BuildStep.IDENTITY));
        assertThat(testCoordinates).containsOnlyKeys(
                "maven/group/artifact/jar/tests/1",
                "maven/group/artifact/pom/1");
        assertThat(testCoordinates.getProperty("maven/group/artifact/jar/tests/1")).isEmpty();
        SequencedProperties testModuleProperties = SequencedProperties.ofFiles(testModule.resolve(BuildStep.MODULE));
        assertThat(testModuleProperties.getProperty("test")).isEqualTo("artifact");
        Path testModuleRequires = testModule.resolve(BuildStep.REQUIRES);
        assertThat(testModuleRequires).exists();
        SequencedProperties testDependencies = SequencedProperties.ofFiles(testModuleRequires);
        assertThat(testDependencies).containsOnlyKeys(
                "main/compile/maven/other/artifact/1",
                "main/runtime/maven/other/artifact/1",
                "main/compile/maven/group/artifact/1",
                "main/runtime/maven/group/artifact/1");
        assertThat(testDependencies.getProperty("main/compile/maven/group/artifact/1")).isEmpty();
    }

    @Test
    public void scopes_are_routed_to_correct_requires_files() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>scope</groupId>
                            <artifactId>compile-dep</artifactId>
                            <version>1</version>
                        </dependency>
                        <dependency>
                            <groupId>scope</groupId>
                            <artifactId>provided-dep</artifactId>
                            <version>1</version>
                            <scope>provided</scope>
                        </dependency>
                        <dependency>
                            <groupId>scope</groupId>
                            <artifactId>runtime-dep</artifactId>
                            <version>1</version>
                            <scope>runtime</scope>
                        </dependency>
                        <dependency>
                            <groupId>scope</groupId>
                            <artifactId>test-dep</artifactId>
                            <version>1</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("src/main/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("src/test/java")).resolve("source"), "bar");
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();

        Path mainRequires = results.get("maven/module-/manifests")
                .resolve(BuildStep.REQUIRES);
        SequencedProperties mainRequiresProps = SequencedProperties.ofFiles(mainRequires);
        assertThat(mainRequiresProps.stringPropertyNames()).containsExactlyInAnyOrder(
                "main/compile/maven/scope/compile-dep/1",
                "main/runtime/maven/scope/compile-dep/1",
                "main/compile/maven/scope/provided-dep/1",
                "main/runtime/maven/scope/runtime-dep/1");

        Path testRequires = results.get("maven/test-module-/manifests")
                .resolve(BuildStep.REQUIRES);
        SequencedProperties testRequiresProps = SequencedProperties.ofFiles(testRequires);
        assertThat(testRequiresProps.stringPropertyNames()).containsExactlyInAnyOrder(
                "main/compile/maven/scope/compile-dep/1",
                "main/runtime/maven/scope/compile-dep/1",
                "main/compile/maven/scope/runtime-dep/1",
                "main/runtime/maven/scope/runtime-dep/1",
                "main/compile/maven/scope/provided-dep/1",
                "main/runtime/maven/scope/provided-dep/1",
                "main/compile/maven/scope/test-dep/1",
                "main/runtime/maven/scope/test-dep/1",
                "main/compile/maven/group/artifact/1",
                "main/runtime/maven/group/artifact/1");
    }

    @Test
    public void exclusions_are_written_for_both_main_and_test_modules() throws IOException {
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
                            <artifactId>lib</artifactId>
                            <version>1</version>
                            <exclusions>
                                <exclusion>
                                    <groupId>excluded</groupId>
                                    <artifactId>transitive</artifactId>
                                </exclusion>
                            </exclusions>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("src/main/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("src/test/java")).resolve("source"), "bar");
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties mainExclusions = SequencedProperties.ofFiles(
                results.get("maven/module-/manifests").resolve(BuildStep.EXCLUSIONS));
        assertThat(mainExclusions.getProperty("main/compile/maven/other/lib/1")).isEqualTo("excluded/transitive");
        assertThat(mainExclusions.getProperty("main/runtime/maven/other/lib/1")).isEqualTo("excluded/transitive");
        Path testExclusions = results.get("maven/test-module-/manifests").resolve(BuildStep.EXCLUSIONS);
        assertThat(testExclusions).exists();
        SequencedProperties testExclusionProps = SequencedProperties.ofFiles(testExclusions);
        assertThat(testExclusionProps.getProperty("main/compile/maven/other/lib/1")).isEqualTo("excluded/transitive");
        assertThat(testExclusionProps.getProperty("main/runtime/maven/other/lib/1")).isEqualTo("excluded/transitive");

        SequencedProperties testRequires = SequencedProperties.ofFiles(
                results.get("maven/test-module-/manifests").resolve(BuildStep.REQUIRES));
        assertThat(testRequires.stringPropertyNames()).contains("main/compile/maven/other/lib/1", "main/runtime/maven/other/lib/1");
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
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module-/manifests", "maven/module-subproject/manifests");
        Path parent = results.get("maven/module-/manifests");
        assertThat(parent.resolve(BuildStep.IDENTITY)).doesNotExist();
        Path parentCoordinatesFolder = results.get("maven/module-/coordinates");
        SequencedProperties parentCoordinates = SequencedProperties.ofFiles(parentCoordinatesFolder.resolve(BuildStep.IDENTITY));
        assertThat(parentCoordinates).containsOnlyKeys(
                "maven/parent/artifact/1",
                "maven/parent/artifact/pom/1");
        assertThat(parentCoordinates.getProperty("maven/parent/artifact/1")).isEmpty();
        assertThat(parent.resolve(BuildStep.REQUIRES)).exists().content().isEmpty();
        Path parentTests = results.get("maven/test-module-/manifests");
        assertThat(parentTests.resolve(BuildStep.IDENTITY)).doesNotExist();
        Path parentTestCoordinatesFolder = results.get("maven/test-module-/coordinates");
        SequencedProperties parentTestCoordinates = SequencedProperties.ofFiles(parentTestCoordinatesFolder.resolve(BuildStep.IDENTITY));
        assertThat(parentTestCoordinates).containsOnlyKeys(
                "maven/parent/artifact/jar/tests/1",
                "maven/parent/artifact/pom/1");
        assertThat(parentTestCoordinates.getProperty("maven/parent/artifact/jar/tests/1")).isEmpty();
        SequencedProperties parentTestModule = SequencedProperties.ofFiles(parentTests.resolve(BuildStep.MODULE));
        assertThat(parentTestModule.getProperty("test")).isEqualTo("artifact");
        SequencedProperties parentTestDependencies = SequencedProperties.ofFiles(parentTests.resolve(BuildStep.REQUIRES));
        assertThat(parentTestDependencies).containsOnlyKeys(
                "main/compile/maven/parent/artifact/1",
                "main/runtime/maven/parent/artifact/1");
        assertThat(parentTestDependencies.getProperty("main/compile/maven/parent/artifact/1")).isEmpty();
        Path child = results.get("maven/module-subproject/manifests");
        assertThat(child.resolve(BuildStep.IDENTITY)).doesNotExist();
        Path childCoordinatesFolder = results.get("maven/module-subproject/coordinates");
        SequencedProperties childCoordinates = SequencedProperties.ofFiles(childCoordinatesFolder.resolve(BuildStep.IDENTITY));
        assertThat(childCoordinates).containsOnlyKeys(
                "maven/group/artifact/1",
                "maven/group/artifact/pom/1");
        assertThat(childCoordinates.getProperty("maven/group/artifact/1")).isEmpty();
        assertThat(child.resolve(BuildStep.REQUIRES)).exists().content().isEmpty();
        Path childTests = results.get("maven/test-module-subproject/manifests");
        assertThat(childTests.resolve(BuildStep.IDENTITY)).doesNotExist();
        Path childTestCoordinatesFolder = results.get("maven/test-module-subproject/coordinates");
        SequencedProperties childTestCoordinates = SequencedProperties.ofFiles(childTestCoordinatesFolder.resolve(BuildStep.IDENTITY));
        assertThat(childTestCoordinates).containsOnlyKeys(
                "maven/group/artifact/jar/tests/1",
                "maven/group/artifact/pom/1");
        assertThat(childTestCoordinates.getProperty("maven/group/artifact/jar/tests/1")).isEmpty();
        SequencedProperties childTestModule = SequencedProperties.ofFiles(childTests.resolve(BuildStep.MODULE));
        assertThat(childTestModule.getProperty("test")).isEqualTo("artifact");
        SequencedProperties childTestDependencies = SequencedProperties.ofFiles(childTests.resolve(BuildStep.REQUIRES));
        assertThat(childTestDependencies).containsOnlyKeys(
                "main/compile/maven/group/artifact/1",
                "main/runtime/maven/group/artifact/1");
        assertThat(childTestDependencies.getProperty("main/compile/maven/group/artifact/1")).isEmpty();
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
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module-/manifests",
                "maven/module-/sources",
                "maven/module-/resources-1");
        assertThat(results.get("maven/module-/sources").resolve(BuildStep.SOURCES + "source")).content().isEqualTo("foo");
        assertThat(results.get("maven/module-/resources-1").resolve(BuildStep.RESOURCES + "resource")).content().isEqualTo("bar");
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
                         <resource>
                           <directory>resources-1</directory>
                         </resource>
                         <resource>
                           <directory>resources-2</directory>
                         </resource>
                       </resources>
                    </build>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("sources")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("resources-1")).resolve("resource1"), "bar");
        Files.writeString(Files.createDirectories(project.resolve("resources-2")).resolve("resource2"), "qux");
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/module-/manifests",
                "maven/module-/sources",
                "maven/module-/resources-1",
                "maven/module-/resources-2");
        assertThat(results.get("maven/module-/sources").resolve(BuildStep.SOURCES + "source")).content().isEqualTo("foo");
        assertThat(results.get("maven/module-/resources-1").resolve(BuildStep.RESOURCES + "resource1")).content().isEqualTo("bar");
        assertThat(results.get("maven/module-/resources-2").resolve(BuildStep.RESOURCES + "resource2")).content().isEqualTo("qux");
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
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/test-module-/manifests",
                "maven/test-module-/sources",
                "maven/test-module-/resources-1");
        assertThat(results.get("maven/test-module-/sources").resolve(BuildStep.SOURCES + "source")).content().isEqualTo("foo");
        assertThat(results.get("maven/test-module-/resources-1").resolve(BuildStep.RESOURCES + "resource")).content().isEqualTo("bar");
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
                         <testResource>
                           <directory>resources-1</directory>
                         </testResource>
                         <testResource>
                           <directory>resources-2</directory>
                         </testResource>
                       </testResources>
                    </build>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("sources")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("resources-1")).resolve("resource1"), "bar");
        Files.writeString(Files.createDirectories(project.resolve("resources-2")).resolve("resource2"), "qux");
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        assertThat(results).containsKeys("maven/test-module-/manifests",
                "maven/test-module-/sources",
                "maven/test-module-/resources-1",
                "maven/test-module-/resources-2");
        assertThat(results.get("maven/test-module-/sources").resolve(BuildStep.SOURCES + "source")).content().isEqualTo("foo");
        assertThat(results.get("maven/test-module-/resources-1").resolve(BuildStep.RESOURCES + "resource1")).content().isEqualTo("bar");
        assertThat(results.get("maven/test-module-/resources-2").resolve(BuildStep.RESOURCES + "resource2")).content().isEqualTo("qux");
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
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        root.addModule("maven", MavenProject.make(project,
                "maven",
                Map.of("maven", new MavenDefaultRepository(repository.toUri(), null, Map.of(), _ -> {})),
                Map.of("maven", new MavenPomResolver()),
                null,
                new HashDigestFunction("MD5"),
                null,
                (descriptor, _, _) -> {
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
                                    "../coordinates",
                                    "../dependencies/artifacts");
                            case "module-bar" -> assertThat(inherited).containsOnlyKeys(
                                    "../sources",
                                    "../manifests",
                                    "../coordinates",
                                    "../dependencies/artifacts",
                                    "../../module-foo/dependencies/prepare",
                                    "../../module-foo/dependencies/artifacts",
                                    "../../module-foo/produce/java/classes",
                                    "../../module-foo/produce/java/artifacts",
                                    "../../module-foo/assign",
                                    "../../module-foo/inventory");
                            default -> fail("Unexpected module: " + descriptor.name());
                        }
                        buildExecutor.addModule("java", new JavaToolchainModule(),
                                "../sources", "../manifests",
                                "../dependencies/artifacts");
                    };
                }));
        SequencedMap<String, Path> results = root.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties foo = SequencedProperties.ofFiles(results
                .get("maven/module-foo/assign")
                .resolve(BuildStep.IDENTITY));
        assertThat(foo.stringPropertyNames()).containsExactly("maven/group/foo/1", "maven/group/foo/pom/1");
        assertThat(foo.getProperty("maven/group/foo/1"))
                .isEqualTo("../../produce/java/artifacts/jar/output/artifacts/classes.jar");
        assertThat(foo.getProperty("maven/group/foo/pom/1"))
                .isEqualTo("../../../../../identifier/scan/output/pom/foo/pom.xml");
        SequencedProperties bar = SequencedProperties.ofFiles(results
                .get("maven/module-bar/assign")
                .resolve(BuildStep.IDENTITY));
        assertThat(bar.stringPropertyNames()).containsExactly("maven/group/bar/1", "maven/group/bar/pom/1");
        assertThat(bar.getProperty("maven/group/bar/1"))
                .isEqualTo("../../produce/java/artifacts/jar/output/artifacts/classes.jar");
        assertThat(bar.getProperty("maven/group/bar/pom/1"))
                .isEqualTo("../../../../../identifier/scan/output/pom/bar/pom.xml");
        assertThat(results.keySet())
                .contains("maven/module-foo/inventory", "maven/module-bar/inventory")
                .doesNotContain("maven/module-foo/coordinates", "maven/module-bar/coordinates");
        SequencedProperties fooInventory = SequencedProperties.ofFiles(results
                .get("maven/module-foo/inventory")
                .resolve("inventory.properties"));
        assertThat(fooInventory.getProperty("module-foo.runtime.0"))
                .endsWith("/classes.jar");
        assertThat(fooInventory.getProperty("module-foo.artifacts.0"))
                .endsWith("/classes.jar");
        SequencedProperties barInventory = SequencedProperties.ofFiles(results
                .get("maven/module-bar/inventory")
                .resolve("inventory.properties"));
        assertThat(barInventory.getProperty("module-bar.runtime.0"))
                .endsWith("/classes.jar");
        assertThat(barInventory.getProperty("module-bar.artifacts.0"))
                .endsWith("/classes.jar");
    }

    @Test
    public void emits_versions_properties_from_dependency_management() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>pinned</groupId>
                                <artifactId>simple</artifactId>
                                <version>2.0</version>
                            </dependency>
                            <dependency>
                                <groupId>pinned</groupId>
                                <artifactId>typed</artifactId>
                                <version>3.0</version>
                                <type>war</type>
                            </dependency>
                            <dependency>
                                <groupId>pinned</groupId>
                                <artifactId>classified</artifactId>
                                <version>4.0</version>
                                <classifier>sources</classifier>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("src/main/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("src/test/java")).resolve("source"), "bar");
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        Path module = results.get("maven/module-/manifests");
        Path compileVersions = module.resolve(BuildStep.VERSIONS);
        assertThat(compileVersions).exists();
        SequencedProperties versions = SequencedProperties.ofFiles(compileVersions);
        assertThat(versions).containsOnly(
                Map.entry("main/compile/maven/pinned/simple", "2.0"),
                Map.entry("main/runtime/maven/pinned/simple", "2.0"),
                Map.entry("main/compile/maven/pinned/typed/war", "3.0"),
                Map.entry("main/runtime/maven/pinned/typed/war", "3.0"),
                Map.entry("main/compile/maven/pinned/classified/jar/sources", "4.0"),
                Map.entry("main/runtime/maven/pinned/classified/jar/sources", "4.0"));
        Path runtimeVersions = module.resolve(BuildStep.VERSIONS);
        assertThat(runtimeVersions).exists();
        SequencedProperties runtime = SequencedProperties.ofFiles(runtimeVersions);
        assertThat(runtime).containsOnly(
                Map.entry("main/compile/maven/pinned/simple", "2.0"),
                Map.entry("main/runtime/maven/pinned/simple", "2.0"),
                Map.entry("main/compile/maven/pinned/typed/war", "3.0"),
                Map.entry("main/runtime/maven/pinned/typed/war", "3.0"),
                Map.entry("main/compile/maven/pinned/classified/jar/sources", "4.0"),
                Map.entry("main/runtime/maven/pinned/classified/jar/sources", "4.0"));
        Path testModule = results.get("maven/test-module-/manifests");
        assertThat(testModule.resolve(BuildStep.VERSIONS)).exists();
        assertThat(testModule.resolve(BuildStep.VERSIONS)).exists();
    }

    @Test
    public void omits_versions_properties_when_no_dependency_management() throws IOException {
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
        Files.writeString(Files.createDirectories(project.resolve("src/test/java")).resolve("source"), "bar");
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        Path module = results.get("maven/module-/manifests");
        assertThat(module.resolve(BuildStep.VERSIONS)).doesNotExist();
        assertThat(module.resolve(BuildStep.VERSIONS)).doesNotExist();
        Path testModule = results.get("maven/test-module-/manifests");
        assertThat(testModule.resolve(BuildStep.VERSIONS)).doesNotExist();
        assertThat(testModule.resolve(BuildStep.VERSIONS)).doesNotExist();
    }

    @Test
    public void extracts_pom_metadata_into_manifests() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <name>Project Name</name>
                    <description>Project description.</description>
                    <url>https://example.com/project</url>
                    <licenses>
                        <license>
                            <name>Apache-2.0</name>
                            <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
                        </license>
                    </licenses>
                    <developers>
                        <developer>
                            <id>alice</id>
                            <name>Alice Example</name>
                            <email>alice@example.com</email>
                        </developer>
                        <developer>
                            <id>bob</id>
                            <name>Bob Example</name>
                            <email>bob@example.com</email>
                        </developer>
                    </developers>
                    <scm>
                        <connection>scm:git:https://example.com/project.git</connection>
                        <developerConnection>scm:git:git@example.com:project.git</developerConnection>
                        <url>https://example.com/project</url>
                    </scm>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("src/main/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("src/test/java")).resolve("source"), "bar");
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        Path module = results.get("maven/module-/manifests");
        Path moduleFile = module.resolve(BuildStep.MODULE);
        assertThat(moduleFile).exists();
        SequencedProperties moduleProperties = SequencedProperties.ofFiles(moduleFile);
        assertThat(moduleProperties).containsOnly(
                Map.entry("path", ""),
                Map.entry("modular", "false"));
        Path metadataFile = module.resolve(BuildStep.METADATA);
        assertThat(metadataFile).exists();
        SequencedProperties metadata = SequencedProperties.ofFiles(metadataFile);
        assertThat(metadata).containsOnly(
                Map.entry("project", "group"),
                Map.entry("artifact", "artifact"),
                Map.entry("version", "1"),
                Map.entry("name", "Project Name"),
                Map.entry("description", "Project description."),
                Map.entry("url", "https://example.com/project"),
                Map.entry("license.apache-2_0.name", "Apache-2.0"),
                Map.entry("license.apache-2_0.url", "https://www.apache.org/licenses/LICENSE-2.0.txt"),
                Map.entry("developer.alice.name", "Alice Example"),
                Map.entry("developer.alice.email", "alice@example.com"),
                Map.entry("developer.bob.name", "Bob Example"),
                Map.entry("developer.bob.email", "bob@example.com"),
                Map.entry("scm.connection", "scm:git:https://example.com/project.git"),
                Map.entry("scm.developerConnection", "scm:git:git@example.com:project.git"),
                Map.entry("scm.url", "https://example.com/project"));
    }

    @Test
    public void checksum_comment_in_dependency_management_lands_in_versions_properties() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>com.example</groupId>
                                <artifactId>pinned</artifactId>
                                <version>2.0.0</version>
                                <!--Checksum/SHA256/cafebabe-->
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("src/main/java")).resolve("source"), "foo");
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties versions = SequencedProperties.ofFiles(results.get("maven/module-/manifests")
                .resolve(BuildStep.VERSIONS));
        assertThat(versions.getProperty("main/compile/maven/com.example/pinned")).isEqualTo("2.0.0 SHA256/cafebabe");
        assertThat(versions.getProperty("main/runtime/maven/com.example/pinned")).isEqualTo("2.0.0 SHA256/cafebabe");
    }

    @Test
    public void checksum_comment_inside_dependency_lands_in_requires_properties() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.11.3</version>
                            <scope>test</scope>
                            <!--Checksum/SHA256/cafebabe-->
                        </dependency>
                        <dependency>
                            <groupId>com.example</groupId>
                            <artifactId>no-pin</artifactId>
                            <version>1.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("src/main/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("src/test/java")).resolve("source"), "bar");
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();

        SequencedProperties testRequires = SequencedProperties.ofFiles(results.get("maven/test-module-/manifests")
                .resolve(BuildStep.REQUIRES));
        assertThat(testRequires.getProperty("main/compile/maven/org.junit.jupiter/junit-jupiter/5.11.3"))
                .isEqualTo("SHA256/cafebabe");
        assertThat(testRequires.getProperty("main/runtime/maven/org.junit.jupiter/junit-jupiter/5.11.3"))
                .isEqualTo("SHA256/cafebabe");

        SequencedProperties mainRequires = SequencedProperties.ofFiles(results.get("maven/module-/manifests")
                .resolve(BuildStep.REQUIRES));
        assertThat(mainRequires.getProperty("main/compile/maven/com.example/no-pin/1.0.0")).isEmpty();
        assertThat(mainRequires.getProperty("main/runtime/maven/com.example/no-pin/1.0.0")).isEmpty();
    }

    @Test
    public void main_class_pom_property_lands_in_module_properties_main_module() throws IOException {
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>group</groupId>
                    <artifactId>artifact</artifactId>
                    <version>1</version>
                    <properties>
                        <mainClass>com.example.Entry</mainClass>
                    </properties>
                </project>
                """);
        Files.writeString(Files.createDirectories(project.resolve("src/main/java")).resolve("source"), "foo");
        Files.writeString(Files.createDirectories(project.resolve("src/test/java")).resolve("source"), "bar");
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties mainModule = SequencedProperties.ofFiles(
                results.get("maven/module-/manifests").resolve(BuildStep.MODULE));
        assertThat(mainModule.getProperty("main")).isEqualTo("com.example.Entry");
        SequencedProperties testModule = SequencedProperties.ofFiles(
                results.get("maven/test-module-/manifests").resolve(BuildStep.MODULE));
        assertThat(testModule.getProperty("main")).isNull();
    }

    @Test
    public void absent_main_class_pom_property_leaves_module_properties_without_main_key() throws IOException {
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
        BuildExecutor executor = BuildExecutor.of(build,
                Duration.ZERO,
                new HashDigestFunction("MD5"),
                BuildStepHashFunction.ofSerializationDigest("MD5"),
                BuildExecutorCallback.nop(), false);
        executor.addModule("maven", new MavenProject(project, "maven", mavenRepository, mavenPomResolver));
        SequencedMap<String, Path> results = executor.execute(Runnable::run).toCompletableFuture().join();
        SequencedProperties mainModule = SequencedProperties.ofFiles(
                results.get("maven/module-/manifests").resolve(BuildStep.MODULE));
        assertThat(mainModule.getProperty("main")).isNull();
    }
}
