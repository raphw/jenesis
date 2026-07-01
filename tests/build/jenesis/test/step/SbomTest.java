package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Checksum;
import build.jenesis.ChecksumStatus;
import build.jenesis.SequencedProperties;
import build.jenesis.step.Sbom;

import static org.assertj.core.api.Assertions.assertThat;

public class SbomTest {

    @TempDir
    private Path root;
    private Path previous, next, supplement, argument;

    @BeforeEach
    public void setUp() throws Exception {
        previous = root.resolve("previous");
        next = Files.createDirectory(root.resolve("next"));
        supplement = Files.createDirectory(root.resolve("supplement"));
        argument = Files.createDirectory(root.resolve("argument"));
    }

    @Test
    public void embeds_a_cyclonedx_sbom_with_dependency_licenses() throws Exception {
        byte[] jarBytes = "jar-bytes".getBytes(StandardCharsets.UTF_8);
        Files.write(Files.createDirectories(argument.resolve("resolved")).resolve("lib.jar"), jarBytes);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jarBytes));

        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("main/compile/maven/org.example/lib/1.2.3", "resolved/lib.jar");
        dependencies.setProperty("main/runtime/maven/org.example/lib/1.2.3", "resolved/lib.jar");
        dependencies.store(argument.resolve(BuildStep.DEPENDENCIES));
        SequencedProperties licenses = new SequencedProperties();
        licenses.setProperty("maven/org.example/lib/1.2.3#0#id", "Apache-2.0");
        licenses.setProperty("maven/org.example/lib/1.2.3#0#category", "permissive");
        licenses.setProperty("maven/org.example/lib/1.2.3#0#name", "Apache License 2.0");
        licenses.setProperty("maven/org.example/lib/1.2.3#0#url", "https://www.apache.org/licenses/LICENSE-2.0.txt");
        licenses.store(argument.resolve("licenses.properties"));
        SequencedProperties graph = new SequencedProperties();
        graph.setProperty("edge/0", "main\tcompile\tmaven\ttrue\tcompile\t1.2.3\t\tmaven/org.example/lib/1.2.3");
        graph.setProperty("vertex/main/compile/maven/org.example/lib", "1.2.3\t\tfalse");
        graph.store(argument.resolve("graph.properties"));
        SequencedProperties metadata = new SequencedProperties();
        metadata.setProperty("project", "build.jenesis");
        metadata.setProperty("artifact", "demo");
        metadata.setProperty("version", "1.0.0");
        metadata.setProperty("name", "Demo");
        metadata.setProperty("description", "A demo project");
        metadata.setProperty("url", "https://example.com/demo");
        metadata.setProperty("license.apache.name", "Apache-2.0");
        metadata.setProperty("license.apache.url", "https://www.apache.org/licenses/LICENSE-2.0.txt");
        metadata.setProperty("developer.raphw.name", "Rafael Winterhalter");
        metadata.setProperty("developer.raphw.email", "rafael.wth@gmail.com");
        metadata.store(argument.resolve(BuildStep.METADATA));

        BuildStepResult result = new Sbom().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(
                                        Path.of(BuildStep.DEPENDENCIES), Checksum.of(ChecksumStatus.ADDED),
                                        Path.of(BuildStep.METADATA), Checksum.of(ChecksumStatus.ADDED))))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();

        Path embedded = next.resolve("resources").resolve("META-INF").resolve("sbom").resolve("demo.cdx.json");
        assertThat(embedded).isNotEmptyFile();
        String sbom = Files.readString(embedded);
        assertThat(sbom)
                .contains("\"purl\": \"pkg:maven/build.jenesis/demo@1.0.0\"")
                .contains("\"purl\": \"pkg:maven/org.example/lib@1.2.3\"")
                .contains("\"content\": \"" + sha256 + "\"")
                .contains("\"id\": \"Apache-2.0\"");
        assertThat(sbom)
                .as("the resolved dependency graph is emitted as CycloneDX relationships")
                .contains("\"bom-ref\": \"org.example/lib/1.2.3\"")
                .contains("{ \"ref\": \"build.jenesis/demo/1.0.0\", \"dependsOn\": [\"org.example/lib/1.2.3\"] }");
        assertThat(sbom)
                .as("the subject component carries the project's own description, developers and website")
                .contains("\"description\": \"A demo project\"")
                .contains("\"name\": \"Rafael Winterhalter\", \"email\": \"rafael.wth@gmail.com\"")
                .contains("{ \"type\": \"website\", \"url\": \"https://example.com/demo\" }")
                .as("a deterministic serial number is derived from the document content")
                .contains("\"serialNumber\": \"urn:uuid:");

        SequencedProperties manifest = SequencedProperties.ofFiles(next.resolve("manifest.mf"));
        assertThat(manifest.getProperty("Sbom-Format")).isEqualTo("CycloneDX");
        assertThat(manifest.getProperty("Sbom-Location")).isEqualTo("META-INF/sbom/demo.cdx.json");

        assertThat(next.resolve("resources").resolve("META-INF").resolve("NOTICE"))
                .content().contains("Demo").contains("Apache-2.0");
        assertThat(next.resolve("reports").resolve("sbom").resolve("demo-1.0.0.cdx.json")).isNotEmptyFile();
    }

    @Test
    public void omits_the_placeholder_snapshot_version_but_still_emits() throws Exception {
        SequencedProperties metadata = new SequencedProperties();
        metadata.setProperty("project", "build.jenesis");
        metadata.setProperty("artifact", "demo");
        metadata.setProperty("version", "1-SNAPSHOT");
        metadata.store(argument.resolve(BuildStep.METADATA));

        BuildStepResult result = new Sbom().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(Path.of(BuildStep.METADATA), Checksum.of(ChecksumStatus.ADDED))))))
                .toCompletableFuture()
                .join();
        assertThat(result.next()).isTrue();

        Path embedded = next.resolve("resources").resolve("META-INF").resolve("sbom").resolve("demo.cdx.json");
        assertThat(embedded).isNotEmptyFile();
        assertThat(Files.readString(embedded))
                .as("the unset 1-SNAPSHOT placeholder is not fabricated into the sbom")
                .doesNotContain("1-SNAPSHOT")
                .doesNotContain("\"version\": \"")
                .as("the subject purl carries no version when none is set")
                .contains("\"purl\": \"pkg:maven/build.jenesis/demo\"");
        assertThat(next.resolve("reports").resolve("sbom").resolve("demo.cdx.json"))
                .as("the standalone report is named without a version suffix")
                .isNotEmptyFile();
    }
}
