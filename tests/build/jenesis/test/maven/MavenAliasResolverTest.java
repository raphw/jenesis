package build.jenesis.test.maven;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.DependencyScope;
import build.jenesis.Repository;
import build.jenesis.RepositoryItem;
import build.jenesis.Resolver;
import build.jenesis.maven.MavenAliasResolver;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenDefaultVersionNegotiator;
import build.jenesis.maven.MavenModuleResolver;
import build.jenesis.maven.MavenPomResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class MavenAliasResolverTest {

    @TempDir
    private Path mavenRepoFolder;

    @TempDir
    private Path work;

    private Resolver resolver;

    @BeforeEach
    public void setUp() {
        resolver = new MavenAliasResolver("maven", new MavenModuleResolver("maven",
                new MavenPomResolver(MavenDefaultVersionNegotiator.maven()),
                (_, _) -> Optional.empty()));
    }

    @Test
    public void resolves_alias_as_synthetic_module_over_maven_artifact() throws IOException {
        addToMavenRepository("org.example", "plain-lib", "1.2.3", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>plain-lib</artifactId>
                    <version>1.2.3</version>
                </project>""");
        addJarToMavenRepository("org.example", "plain-lib", "1.2.3");

        Resolver.Resolution resolution = resolver.dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), mavenRepoFolder, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("toolkit.lib", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of(
                        Resolver.ALIAS + "toolkit.lib", "org.example/plain-lib",
                        "org.example/plain-lib", "1.2.3")),
                DependencyScope.COMPILE);

        assertThat(resolution.artifacts()).containsOnlyKeys(
                "maven/org.example/plain-lib/1.2.3",
                "module/toolkit.lib");
        Resolver.Resolved alias = resolution.artifacts().get("module/toolkit.lib");
        assertThat(alias.internal()).isTrue();
        ModuleDescriptor descriptor = ModuleFinder.of(alias.file()).findAll().iterator().next().descriptor();
        assertThat(descriptor.name()).isEqualTo("toolkit.lib");
        assertThat(descriptor.isAutomatic()).isTrue();
        assertThat(resolution.artifacts().get("maven/org.example/plain-lib/1.2.3").internal()).isFalse();
        assertThat(resolution.vertices().sequencedKeySet())
                .contains("module/toolkit.lib")
                .noneMatch(vertex -> vertex.contains(MavenAliasResolver.GROUP));
        assertThat(resolution.edges()).noneMatch(edge ->
                edge.coordinate().contains(MavenAliasResolver.GROUP)
                        || edge.parent() != null && edge.parent().contains(MavenAliasResolver.GROUP));
    }

    @Test
    public void resolves_alias_target_transitives() throws IOException {
        addToMavenRepository("org.example", "plain-lib", "1.0", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>plain-lib</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.transitive</groupId>
                            <artifactId>lib</artifactId>
                            <version>2.0</version>
                        </dependency>
                    </dependencies>
                </project>""");
        addToMavenRepository("org.transitive", "lib", "2.0", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.transitive</groupId>
                    <artifactId>lib</artifactId>
                    <version>2.0</version>
                </project>""");
        addJarToMavenRepository("org.example", "plain-lib", "1.0");
        addJarToMavenRepository("org.transitive", "lib", "2.0");

        Resolver.Resolution resolution = resolver.dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), mavenRepoFolder, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("toolkit.lib", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of(
                        Resolver.ALIAS + "toolkit.lib", "org.example/plain-lib",
                        "org.example/plain-lib", "1.0")),
                DependencyScope.COMPILE);

        assertThat(resolution.artifacts()).containsOnlyKeys(
                "maven/org.example/plain-lib/1.0",
                "maven/org.transitive/lib/2.0",
                "module/toolkit.lib");
    }

    @Test
    public void threads_target_checksum_from_pin() throws IOException {
        addToMavenRepository("org.example", "plain-lib", "1.0", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>plain-lib</artifactId>
                    <version>1.0</version>
                </project>""");
        String checksum = "SHA-256/" + addJarToMavenRepository("org.example", "plain-lib", "1.0");

        Resolver.Resolution resolution = resolver.dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), mavenRepoFolder, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("toolkit.lib", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of(
                        Resolver.ALIAS + "toolkit.lib", "org.example/plain-lib",
                        "org.example/plain-lib", "1.0 " + checksum)),
                DependencyScope.COMPILE);

        assertThat(resolution.artifacts().get("maven/org.example/plain-lib/1.0").checksum()).isEqualTo(checksum);
    }

    @Test
    public void rejects_alias_without_target_version() {
        assertThatThrownBy(() -> resolver.dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), mavenRepoFolder, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("toolkit.lib", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of(Resolver.ALIAS + "toolkit.lib", "org.example/plain-lib")),
                DependencyScope.COMPILE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No version for org.example/plain-lib");
    }

    @Test
    public void rejects_pin_of_the_alias_name() {
        assertThatThrownBy(() -> resolver.dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), mavenRepoFolder, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("toolkit.lib", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of(
                        Resolver.ALIAS + "toolkit.lib", "org.example/plain-lib",
                        "toolkit.lib", "1.0")),
                DependencyScope.COMPILE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pin the target instead");
    }

    @Test
    public void unaliased_resolution_is_delegated_untouched() throws IOException {
        addToMavenRepository("org.example", "plain-lib", "1.0", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>plain-lib</artifactId>
                    <version>1.0</version>
                </project>""");
        addJarToMavenRepository("org.example", "plain-lib", "1.0");
        Repository discovery = (_, coordinate) -> coordinate.equals("foo.bar:pom")
                ? Optional.of((RepositoryItem) () -> new ByteArrayInputStream("""
                        <project xmlns="http://maven.apache.org/POM/4.0.0">
                            <groupId>org.example</groupId>
                            <artifactId>plain-lib</artifactId>
                            <version>1.0</version>
                        </project>""".getBytes(StandardCharsets.UTF_8)))
                : Optional.empty();

        Resolver.Resolution resolution = new MavenAliasResolver("maven", new MavenModuleResolver("maven",
                new MavenPomResolver(MavenDefaultVersionNegotiator.maven()), discovery)).dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), mavenRepoFolder, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("foo.bar", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(),
                DependencyScope.COMPILE);

        assertThat(resolution.artifacts()).containsOnlyKeys(
                "maven/org.example/plain-lib/1.0",
                "module/foo.bar/1.0");
    }

    @Test
    public void compiled_module_reads_aliased_non_modular_artifact() throws IOException {
        Path libClasses = compile("plain", "toollib/Lib.java", """
                package toollib;
                public class Lib {
                    public static String value() {
                        return "from-lib";
                    }
                }
                """);
        addToMavenRepository("org.example", "plain-lib", "1.0", """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>plain-lib</artifactId>
                    <version>1.0</version>
                </project>""");
        jar(Files.createDirectories(mavenRepoFolder.resolve("org/example/plain-lib/1.0"))
                .resolve("plain-lib-1.0.jar"), libClasses);

        Resolver.Resolution resolution = resolver.dependencies(
                Runnable::run,
                "module",
                Map.of("maven", new MavenDefaultRepository(mavenRepoFolder.toUri(), mavenRepoFolder, Map.of(), _ -> {})),
                new LinkedHashMap<>(Map.of("toolkit.lib", Collections.emptyNavigableSet())),
                new LinkedHashMap<>(Map.of(
                        Resolver.ALIAS + "toolkit.lib", "org.example/plain-lib",
                        "org.example/plain-lib", "1.0")),
                DependencyScope.COMPILE);

        Path sources = Files.createDirectories(work.resolve("app-sources"));
        Files.writeString(sources.resolve("module-info.java"), """
                module my.app {
                    requires toolkit.lib;
                }
                """);
        Files.createDirectories(sources.resolve("myapp"));
        Files.writeString(sources.resolve("myapp/Main.java"), """
                package myapp;
                public class Main {
                    public static String read() {
                        return toollib.Lib.value();
                    }
                }
                """);
        String modulePath = resolution.artifacts().values().stream()
                .map(resolved -> resolved.file().toString())
                .collect(Collectors.joining(File.pathSeparator));
        StringWriter errors = new StringWriter();
        int result = ToolProvider.findFirst("javac").orElseThrow().run(
                new PrintWriter(Writer.nullWriter()),
                new PrintWriter(errors),
                "-d", Files.createDirectories(work.resolve("app-classes")).toString(),
                "-p", modulePath,
                sources.resolve("module-info.java").toString(),
                sources.resolve("myapp/Main.java").toString());
        assertThat(result).withFailMessage(() -> "Compilation failed: " + errors).isEqualTo(0);
    }

    private void addToMavenRepository(String groupId, String artifactId, String version, String pom) throws IOException {
        Files.writeString(Files
                .createDirectories(mavenRepoFolder.resolve(groupId.replace('.', '/') + "/" + artifactId + "/" + version))
                .resolve(artifactId + "-" + version + ".pom"), pom);
    }

    private String addJarToMavenRepository(String groupId, String artifactId, String version) throws IOException {
        byte[] content = (groupId + ":" + artifactId + ":" + version).getBytes(StandardCharsets.UTF_8);
        Files.write(Files
                .createDirectories(mavenRepoFolder.resolve(groupId.replace('.', '/') + "/" + artifactId + "/" + version))
                .resolve(artifactId + "-" + version + ".jar"), content);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path compile(String name, String file, String source) throws IOException {
        Path sources = Files.createDirectories(work.resolve(name + "-sources"));
        Path classes = Files.createDirectories(work.resolve(name + "-classes"));
        Path java = sources.resolve(file);
        Files.createDirectories(java.getParent());
        Files.writeString(java, source);
        StringWriter errors = new StringWriter();
        int result = ToolProvider.findFirst("javac").orElseThrow().run(
                new PrintWriter(Writer.nullWriter()),
                new PrintWriter(errors),
                "-d", classes.toString(),
                java.toString());
        if (result != 0) {
            throw new IllegalStateException("Compilation failed: " + errors);
        }
        return classes;
    }

    private void jar(Path file, Path classes) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(file));
             Stream<Path> stream = Files.walk(classes)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(path).toString().replace(File.separatorChar, '/')));
                output.write(Files.readAllBytes(path));
                output.closeEntry();
            }
        }
    }
}
