package build.jenesis.test.step;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
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
        licenses.setProperty("maven/org.example/lib/1.2.3#0#name", "Apache-2.0");
        licenses.setProperty("maven/org.example/lib/1.2.3#0#url", "https://www.apache.org/licenses/LICENSE-2.0.txt");
        licenses.store(argument.resolve("licenses.properties"));
        SequencedProperties metadata = new SequencedProperties();
        metadata.setProperty("project", "build.jenesis");
        metadata.setProperty("artifact", "demo");
        metadata.setProperty("version", "1.0.0");
        metadata.setProperty("name", "Demo");
        metadata.setProperty("url", "https://example.com/demo");
        metadata.setProperty("license.apache.name", "Apache-2.0");
        metadata.setProperty("license.apache.url", "https://www.apache.org/licenses/LICENSE-2.0.txt");
        metadata.store(argument.resolve(BuildStep.METADATA));

        BuildStepResult result = new Sbom().apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(
                                        Path.of(BuildStep.DEPENDENCIES), ChecksumStatus.ADDED,
                                        Path.of(BuildStep.METADATA), ChecksumStatus.ADDED)))))
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

        SequencedProperties manifest = SequencedProperties.ofFiles(next.resolve("manifest.mf"));
        assertThat(manifest.getProperty("Sbom-Format")).isEqualTo("CycloneDX");
        assertThat(manifest.getProperty("Sbom-Location")).isEqualTo("META-INF/sbom/demo.cdx.json");

        assertThat(next.resolve("resources").resolve("META-INF").resolve("NOTICE"))
                .content().contains("Demo").contains("Apache-2.0");
        assertThat(next.resolve("sbom").resolve("demo-1.0.0.cdx.json")).isNotEmptyFile();
    }
}
