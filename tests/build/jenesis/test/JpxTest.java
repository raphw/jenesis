package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import java.util.jar.Attributes;
import build.jenesis.docker.DockerizedJava;
import build.jenesis.HashDigestFunction;
import build.jenesis.Jpx;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.MavenDefaultRepository;
import build.jenesis.maven.MavenModuleResolver;
import build.jenesis.maven.MavenPomResolver;
import build.jenesis.module.JenesisModuleRepository;
import build.jenesis.module.ModularJarResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JpxTest {

    private static final String LIB_SOURCE = """
            package toollib;
            public class Lib {
                public static String value() {
                    return "from-lib";
                }
            }
            """;

    private static final String MAIN_SOURCE = """
            package toolmain;
            public class Main {
                public static void main(String[] arguments) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(arguments[0]), toollib.Lib.value());
                    System.exit(7);
                }
            }
            """;

    private static final String PLAIN_SOURCE = """
            package plaintool;
            public class Main {
                public static void main(String[] arguments) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(arguments[0]), "plain");
                    System.exit(5);
                }
            }
            """;

    @TempDir
    private Path storage;

    @TempDir
    private Path mavenRepoFolder;

    @TempDir
    private Path jenesisRepoFolder;

    @TempDir
    private Path work;

    private Path libJar, toolJar, plainJar;

    private int counter;

    @BeforeEach
    public void setUp() throws IOException {
        Path libClasses = compile("toollib/Lib.java", LIB_SOURCE);
        libJar = jar("tool-lib-1.0.jar", ClassFile.of().buildModule(ModuleAttribute.of(
                ModuleDesc.of("tool.lib"),
                builder -> {
                    builder.moduleVersion("1.0");
                    builder.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), 0, null));
                    builder.exports(ModuleExportInfo.of(PackageDesc.of("toollib"), 0));
                })), null, libClasses);
        toolJar = jar("tool-main-1.0.jar", ClassFile.of().buildModule(ModuleAttribute.of(
                        ModuleDesc.of("tool.main"),
                        builder -> {
                            builder.moduleVersion("1.0");
                            builder.requires(ModuleRequireInfo.of(ModuleDesc.of("java.base"), 0, null));
                            builder.requires(ModuleRequireInfo.of(ModuleDesc.of("tool.lib"), 0, "1.0"));
                        }),
                classBuilder -> classBuilder.with(ModuleMainClassAttribute.of(ClassDesc.of("toolmain.Main")))),
                null, compile("toolmain/Main.java", MAIN_SOURCE, libClasses));
        plainJar = jar("plain-tool.jar", null, "plaintool.Main", compile("plaintool/Main.java", PLAIN_SOURCE));
    }

    @Test
    public void installs_and_launches_maven_coordinates() throws IOException, InterruptedException {
        addMavenTool();
        Jpx jpx = jpx();

        Path folder = jpx.install(Jpx.Command.of("org.example:tool-main@1.0"), false);

        assertThat(folder.getFileName().toString()).isEqualTo("org.example--tool-main@1.0");
        SequencedProperties properties = SequencedProperties.ofFiles(folder.resolve(Jpx.PROPERTIES));
        assertThat(properties.getProperty("name")).isEqualTo("org.example:tool-main");
        assertThat(properties.getProperty("version")).isEqualTo("1.0");
        assertThat(properties.getProperty("mainModule")).isEqualTo("tool.main");
        assertThat(properties.getProperty("mainClass")).isEqualTo("toolmain.Main");
        assertThat(properties.getProperty("modulepath")).isEqualTo("tool-lib-1.0.jar,tool-main-1.0.jar");
        assertThat(properties.getProperty("selfContainedModuleGraph")).isEqualTo("true");
        assertThat(properties.getProperty("classpath")).isNull();
        assertThat(properties.getProperty("checksum")).startsWith("SHA-256/");

        Path marker = work.resolve("marker.txt");
        assertThat(jpx.launch(folder, List.of(marker.toString()))).isEqualTo(7);
        assertThat(marker).hasContent("from-lib");
    }

    @Test
    public void resolves_latest_release_without_version() throws IOException, InterruptedException {
        addPlainTool("2.0");
        addMavenMetadata("plain-tool", "2.0", "1.0", "2.0");
        Jpx jpx = jpx();

        Path folder = jpx.install(Jpx.Command.of("org.example:plain-tool"), false);

        assertThat(folder.getFileName().toString()).isEqualTo("org.example--plain-tool@2.0");
        SequencedProperties properties = SequencedProperties.ofFiles(folder.resolve(Jpx.PROPERTIES));
        assertThat(properties.getProperty("mainModule")).isNull();
        assertThat(properties.getProperty("mainClass")).isEqualTo("plaintool.Main");
        assertThat(properties.getProperty("classpath")).isEqualTo("plain-tool-2.0.jar");

        Path marker = work.resolve("marker.txt");
        assertThat(jpx.launch(folder, List.of(marker.toString()))).isEqualTo(5);
        assertThat(marker).hasContent("plain");
    }

    @Test
    public void installs_and_launches_module_name() throws IOException, InterruptedException {
        addMavenTool();
        addDiscoveryPom(null);
        Jpx jpx = jpx();

        Path folder = jpx.install(Jpx.Command.of("tool.main"), false);

        assertThat(folder.getFileName().toString()).isEqualTo("tool.main@1.0");
        SequencedProperties properties = SequencedProperties.ofFiles(folder.resolve(Jpx.PROPERTIES));
        assertThat(properties.getProperty("mainModule")).isEqualTo("tool.main");
        assertThat(properties.getProperty("modulepath")).isEqualTo("tool-lib-1.0.jar,tool-main-1.0.jar");

        Path marker = work.resolve("marker.txt");
        assertThat(jpx.launch(folder, List.of(marker.toString()))).isEqualTo(7);
        assertThat(marker).hasContent("from-lib");
    }

    @Test
    public void installs_and_launches_pure_modular() throws IOException, InterruptedException {
        addModularJars(true);
        Jpx jpx = jpx();

        Path folder = jpx.install(Jpx.Command.of("tool.main@1.0"), true);

        assertThat(folder.getFileName().toString()).isEqualTo("tool.main@1.0");
        SequencedProperties properties = SequencedProperties.ofFiles(folder.resolve(Jpx.PROPERTIES));
        assertThat(properties.getProperty("mainModule")).isEqualTo("tool.main");
        assertThat(properties.getProperty("modulepath")).isEqualTo("tool.lib.jar,tool.main.jar");
        assertThat(properties.getProperty("selfContainedModuleGraph")).isEqualTo("true");

        Path marker = work.resolve("marker.txt");
        assertThat(jpx.launch(folder, List.of(marker.toString()))).isEqualTo(7);
        assertThat(marker).hasContent("from-lib");
    }

    @Test
    public void pure_modular_uses_declared_version_without_pin() throws IOException {
        addModularJars(false);
        Jpx jpx = jpx();

        Path folder = jpx.install(Jpx.Command.of("tool.main"), true);

        assertThat(folder.getFileName().toString()).isEqualTo("tool.main@1.0");
    }

    @Test
    public void prefers_latest_installed_without_version() throws IOException {
        addPlainTool("1.0");
        addPlainTool("2.0");
        Jpx jpx = jpx();
        Path older = jpx.install(Jpx.Command.of("org.example:plain-tool@2.0"), false);
        Path newer = jpx.install(Jpx.Command.of("org.example:plain-tool@1.0"), false);
        Files.setLastModifiedTime(newer.resolve(Jpx.PROPERTIES), FileTime.from(Instant.now().plusSeconds(60)));
        Repository offline = (_, coordinate) -> {
            throw new IOException("Offline, but fetched: " + coordinate);
        };
        Jpx offlineJpx = new Jpx(storage,
                Map.of("maven", offline, "module", offline, "modular", offline),
                Map.of("maven", new MavenPomResolver()),
                new HashDigestFunction("SHA-256"));

        assertThat(offlineJpx.install(Jpx.Command.of("org.example:plain-tool"), false)).isEqualTo(newer);
        assertThat(older).isNotEqualTo(newer);
    }

    @Test
    public void redoes_broken_installation() throws IOException, InterruptedException {
        addMavenTool();
        Jpx jpx = jpx();
        Path folder = jpx.install(Jpx.Command.of("org.example:tool-main@1.0"), false);
        Files.delete(folder.resolve(Jpx.PROPERTIES));

        assertThat(jpx.install(Jpx.Command.of("org.example:tool-main@1.0"), false)).isEqualTo(folder);
        assertThat(folder.resolve(Jpx.PROPERTIES)).isRegularFile();

        Path marker = work.resolve("marker.txt");
        assertThat(jpx.launch(folder, List.of(marker.toString()))).isEqualTo(7);
    }

    @Test
    public void verifies_checksum_prefix_and_rejects_mismatch() throws IOException {
        addMavenTool();
        Jpx jpx = jpx();
        Path folder = jpx.install(Jpx.Command.of("org.example:tool-main@1.0"), false);
        String checksum = SequencedProperties.ofFiles(folder.resolve(Jpx.PROPERTIES))
                .getProperty("checksum")
                .substring("SHA-256/".length());

        assertThat(jpx.install(Jpx.Command.of("org.example:tool-main/" + checksum.substring(0, 32) + "@1.0"), false))
                .isEqualTo(folder);
        assertThatThrownBy(() -> jpx.install(Jpx.Command.of("org.example:tool-main/" + "0".repeat(32) + "@1.0"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Checksum mismatch");
    }

    @Test
    public void rejects_insecure_or_malformed_checksum() {
        assertThatThrownBy(() -> Jpx.Command.of("tool.main/" + "0".repeat(31) + "@1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 hex characters");
        assertThatThrownBy(() -> Jpx.Command.of("tool.main/" + "x".repeat(32) + "@1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a hexadecimal checksum");
    }

    @Test
    @EnabledIf("dockerAvailable")
    public void launches_in_docker() throws IOException, InterruptedException {
        addMavenTool();
        Jpx jpx = jpx();
        Path folder = jpx.install(Jpx.Command.of("org.example:tool-main@1.0"), false);

        Path marker = work.resolve("marker.txt");
        assertThat(jpx.launch(folder, List.of(marker.toString()), new DockerizedJava(work))).isEqualTo(7);
        assertThat(marker).hasContent("from-lib");
    }

    static boolean dockerAvailable() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return false;
        }
        try {
            Process process = new ProcessBuilder("docker", "info")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException _) {
            return false;
        }
    }

    @Test
    public void rejects_coordinates_with_modular_switch() {
        assertThatThrownBy(() -> jpx().install(Jpx.Command.of("org.example:tool-main@1.0"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Pure module resolution requires a module name");
    }

    @Test
    public void refuses_target_without_main_class() throws IOException {
        addMavenTool();

        assertThatThrownBy(() -> jpx().install(Jpx.Command.of("org.example:tool-lib@1.0"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No main class");
    }

    private Jpx jpx() {
        MavenPomResolver maven = new MavenPomResolver();
        Repository mavenRepository = new MavenDefaultRepository(mavenRepoFolder.toUri(), mavenRepoFolder, Map.of(), _ -> {});
        Repository jenesisRepository = new JenesisModuleRepository(jenesisRepoFolder.toUri());
        return new Jpx(storage,
                Map.of("maven", mavenRepository, "module", jenesisRepository, "modular", jenesisRepository),
                Map.of("maven", maven,
                        "module", new MavenModuleResolver("maven", maven, jenesisRepository),
                        "modular", new ModularJarResolver(false)),
                new HashDigestFunction("SHA-256"));
    }

    private void addMavenTool() throws IOException {
        addToMavenRepository("org.example", "tool-lib", "1.0", libJar, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>tool-lib</artifactId>
                    <version>1.0</version>
                </project>""");
        addToMavenRepository("org.example", "tool-main", "1.0", toolJar, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>tool-main</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>tool-lib</artifactId>
                            <version>1.0</version>
                        </dependency>
                    </dependencies>
                </project>""");
    }

    private void addPlainTool(String version) throws IOException {
        addToMavenRepository("org.example", "plain-tool", version, plainJar, """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>plain-tool</artifactId>
                    <version>%s</version>
                </project>""".formatted(version));
    }

    private void addToMavenRepository(String groupId, String artifactId, String version, Path jar, String pom) throws IOException {
        Path folder = Files.createDirectories(mavenRepoFolder
                .resolve(groupId.replace('.', '/') + "/" + artifactId + "/" + version));
        Files.writeString(folder.resolve(artifactId + "-" + version + ".pom"), pom);
        Files.copy(jar, folder.resolve(artifactId + "-" + version + ".jar"));
    }

    private void addMavenMetadata(String artifactId, String release, String... versions) throws IOException {
        Files.writeString(Files.createDirectories(mavenRepoFolder.resolve("org/example/" + artifactId))
                .resolve("maven-metadata.xml"), """
                <metadata>
                    <groupId>org.example</groupId>
                    <artifactId>%s</artifactId>
                    <versioning>
                        <latest>%s</latest>
                        <release>%s</release>
                        <versions>
                %s        </versions>
                    </versioning>
                </metadata>""".formatted(artifactId, release, release, Arrays.stream(versions)
                .map(version -> "            <version>" + version + "</version>\n")
                .collect(Collectors.joining())));
    }

    /**
     * Lays out the tool as a local jenesis repository: a discovery POM for the {@code module} prefix and
     * the module jars for the {@code modular} prefix, versioned or not.
     */
    private void addDiscoveryPom(String version) throws IOException {
        Path folder = Files.createDirectories(version == null
                ? jenesisRepoFolder.resolve("tool.main")
                : jenesisRepoFolder.resolve("tool.main/" + version));
        // The discovery POM is the artifact's full POM: the graph is read from it directly,
        // never refetched from the Maven repository.
        Files.writeString(folder.resolve("tool.main.pom"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <groupId>org.example</groupId>
                    <artifactId>tool-main</artifactId>
                    <version>1.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.example</groupId>
                            <artifactId>tool-lib</artifactId>
                            <version>1.0</version>
                        </dependency>
                    </dependencies>
                </project>""");
    }

    private void addModularJars(boolean versioned) throws IOException {
        Files.copy(toolJar, Files.createDirectories(versioned
                ? jenesisRepoFolder.resolve("tool.main/1.0")
                : jenesisRepoFolder.resolve("tool.main")).resolve("tool.main.jar"));
        Files.copy(libJar, Files.createDirectories(jenesisRepoFolder.resolve("tool.lib/1.0")).resolve("tool.lib.jar"));
    }

    private Path compile(String file, String source, Path... classpath) throws IOException {
        Path sources = Files.createDirectories(work.resolve("sources-" + counter));
        Path classes = Files.createDirectories(work.resolve("classes-" + counter++));
        Path java = sources.resolve(file);
        Files.createDirectories(java.getParent());
        Files.writeString(java, source);
        List<String> arguments = new ArrayList<>(List.of("-d", classes.toString()));
        if (classpath.length > 0) {
            arguments.add("-cp");
            arguments.add(Arrays.stream(classpath).map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        }
        arguments.add(java.toString());
        StringWriter errors = new StringWriter();
        int result = ToolProvider.findFirst("javac").orElseThrow().run(
                new PrintWriter(Writer.nullWriter()),
                new PrintWriter(errors),
                arguments.toArray(String[]::new));
        if (result != 0) {
            throw new IllegalStateException("Compilation failed: " + errors);
        }
        return classes;
    }

    private Path jar(String name, byte[] moduleInfo, String mainClass, Path classes) throws IOException {
        Path file = work.resolve(name);
        JarOutputStream output;
        if (mainClass == null) {
            output = new JarOutputStream(Files.newOutputStream(file));
        } else {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
            output = new JarOutputStream(Files.newOutputStream(file), manifest);
        }
        try (output) {
            if (moduleInfo != null) {
                output.putNextEntry(new JarEntry("module-info.class"));
                output.write(moduleInfo);
                output.closeEntry();
            }
            try (Stream<Path> stream = Files.walk(classes)) {
                for (Path path : stream.filter(Files::isRegularFile).toList()) {
                    output.putNextEntry(new JarEntry(classes.relativize(path).toString().replace(File.separatorChar, '/')));
                    output.write(Files.readAllBytes(path));
                    output.closeEntry();
                }
            }
        }
        return file;
    }
}
