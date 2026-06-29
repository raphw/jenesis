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
import build.jenesis.step.LicenseCheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class LicenseCheckTest {

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

    private BuildStepResult run(LicenseCheck step) throws Exception {
        return step.apply(Runnable::run,
                        new BuildStepContext(previous, next, supplement),
                        new LinkedHashMap<>(Map.of("argument", new BuildStepArgument(
                                argument,
                                Map.of(Path.of(BuildStep.DEPENDENCIES), Checksum.of(ChecksumStatus.ADDED))))))
                .toCompletableFuture()
                .join();
    }

    private void resolve(String coordinate, String... licenseNames) throws IOException {
        String[][] licenses = new String[licenseNames.length][];
        for (int index = 0; index < licenseNames.length; index++) {
            licenses[index] = new String[]{licenseNames[index], null};
        }
        resolveLicensed(coordinate, licenses);
    }

    private void resolveLicensed(String coordinate, String[]... licenses) throws IOException {
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("main/compile/maven/" + coordinate, "resolved/lib.jar");
        dependencies.store(argument.resolve(BuildStep.DEPENDENCIES));
        SequencedProperties properties = new SequencedProperties();
        for (int index = 0; index < licenses.length; index++) {
            if (licenses[index][0] != null) {
                properties.setProperty("maven/" + coordinate + "#" + index + "#name", licenses[index][0]);
            }
            if (licenses[index].length > 1 && licenses[index][1] != null) {
                properties.setProperty("maven/" + coordinate + "#" + index + "#url", licenses[index][1]);
            }
        }
        properties.store(argument.resolve("licenses.properties"));
    }

    private Path report() {
        return next.resolve("reports").resolve("compliance").resolve("licenses.txt");
    }

    @Test
    public void allows_a_dependency_whose_license_is_on_the_allow_list() throws Exception {
        resolve("org.example/lib/1.2.3", "Apache License, Version 2.0");
        assertThat(run(new LicenseCheck().allowed(new LinkedHashSet<>(List.of("Apache", "MIT")))).next()).isTrue();
        assertThat(report()).content().contains("org.example/lib/1.2.3 [OK]");
    }

    @Test
    public void denies_a_dependency_whose_license_is_not_on_the_allow_list() throws Exception {
        resolve("org.example/lib/1.2.3", "Eclipse Public License 2.0");
        assertThatThrownBy(() -> run(new LicenseCheck().allowed(new LinkedHashSet<>(List.of("Apache")))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void normalizes_a_declared_name_to_its_spdx_id() throws Exception {
        resolve("org.example/lib/1.2.3", "Apache License, Version 2.0");
        assertThat(run(new LicenseCheck().allowed(new LinkedHashSet<>(List.of("Apache-2.0")))).next()).isTrue();
    }

    @Test
    public void matches_on_the_license_url_when_the_name_is_unhelpful() throws Exception {
        resolveLicensed("org.example/lib/1.2.3", new String[]{"LICENSE", "https://www.apache.org/licenses/LICENSE-2.0"});
        assertThat(run(new LicenseCheck().allowed(new LinkedHashSet<>(List.of("Apache-2.0")))).next()).isTrue();
    }

    @Test
    public void matches_by_license_category() throws Exception {
        resolve("org.example/lib/1.2.3", "The MIT License");
        assertThat(run(new LicenseCheck().allowed(new LinkedHashSet<>(List.of("permissive")))).next()).isTrue();
    }

    @Test
    public void accepts_any_allowed_license_under_or_semantics() throws Exception {
        resolveLicensed("org.example/lib/1.2.3",
                new String[]{"GNU General Public License v3.0", null},
                new String[]{"The MIT License", null});
        assertThat(run(new LicenseCheck().allowed(new LinkedHashSet<>(List.of("MIT")))).next()).isTrue();
    }

    @Test
    public void denies_a_license_on_the_deny_list() throws Exception {
        resolve("org.example/lib/1.2.3", "GNU General Public License v3.0");
        assertThatThrownBy(() -> run(new LicenseCheck().denied(new LinkedHashSet<>(List.of("strong-copyleft")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org.example/lib/1.2.3");
    }

    @Test
    public void fails_on_a_missing_license_by_default() throws Exception {
        resolve("org.example/lib/1.2.3");
        assertThatThrownBy(() -> run(new LicenseCheck()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no license");
    }

    @Test
    public void unknown_ignore_does_not_fail_on_a_missing_license() throws Exception {
        resolve("org.example/lib/1.2.3");
        assertThat(run(new LicenseCheck().unknown(LicenseCheck.Unknown.IGNORE)).next()).isTrue();
    }

    @Test
    public void an_override_supplies_a_license_for_a_coordinate() throws Exception {
        resolve("org.example/lib/1.2.3");
        assertThat(run(new LicenseCheck().overrides(Map.of("maven/org.example/lib", "Apache-2.0"))).next()).isTrue();
        assertThat(report()).content().contains("[OK]").contains("Apache-2.0");
    }

    @Test
    public void configured_reads_the_licensing_properties_file() throws Exception {
        Path configuration = Files.createDirectory(root.resolve("configuration"));
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("allowed", "Apache-2.0,MIT");
        properties.setProperty("override.maven/org.example/lib", "Apache-2.0");
        properties.store(configuration.resolve("licensing.properties"));
        resolve("org.example/lib/1.2.3");
        LicenseCheck step = LicenseCheck.configured(configuration);
        assertThat(step).isNotNull();
        assertThat(run(step).next()).isTrue();
        assertThat(report()).content().contains("[OK]");
    }

    @Test
    public void configured_returns_null_without_a_licensing_properties_file() throws Exception {
        assertThat(LicenseCheck.configured(Files.createDirectory(root.resolve("configuration")))).isNull();
    }

    @Test
    public void reads_an_embedded_sbom_license_when_no_pom_license_is_declared() throws Exception {
        Path jar = argument.resolve("resolved/lib.jar");
        resolveJarOnly("jenesis", "org.example.lib/1.0.0", jar);
        writeSbomJar(jar, "META-INF/sbom/lib.cdx.json", """
                {
                  "bomFormat": "CycloneDX",
                  "specVersion": "1.6",
                  "metadata": {
                    "component": {
                      "type": "library",
                      "name": "org.example.lib",
                      "version": "1.0.0",
                      "licenses": [ { "license": { "id": "Apache-2.0" } } ]
                    }
                  }
                }
                """);
        assertThat(run(new LicenseCheck().allowed(new LinkedHashSet<>(List.of("Apache-2.0")))).next()).isTrue();
        assertThat(report()).content().contains("org.example.lib/1.0.0 [OK]").contains("Apache-2.0");
    }

    @Test
    public void reads_an_embedded_sbom_license_in_name_and_url_form() throws Exception {
        Path jar = argument.resolve("resolved/lib.jar");
        resolveJarOnly("jenesis", "org.example.lib/2.0.0", jar);
        writeSbomJar(jar, "META-INF/sbom/lib.cdx.json", """
                {
                  "metadata": {
                    "component": {
                      "licenses": [ { "license": { "name": "Custom", "url": "https://opensource.org/licenses/MIT" } } ]
                    }
                  }
                }
                """);
        assertThat(run(new LicenseCheck().allowed(new LinkedHashSet<>(List.of("MIT")))).next()).isTrue();
    }

    @Test
    public void reads_a_license_text_file_when_the_manifest_has_no_license() throws Exception {
        Path jar = argument.resolve("resolved/lib.jar");
        resolveJarOnly("jenesis", "org.example.lib/3.0.0", jar);
        writeFileJar(jar, "META-INF/LICENSE", """
                Apache License
                Version 2.0, January 2004
                http://www.apache.org/licenses/
                """);
        assertThat(run(new LicenseCheck().allowed(new LinkedHashSet<>(List.of("Apache-2.0")))).next()).isTrue();
        assertThat(report()).content().contains("org.example.lib/3.0.0 [OK]").contains("Apache-2.0");
    }

    private void resolveJarOnly(String repository, String coordinate, Path jar) throws IOException {
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("main/compile/" + repository + "/" + coordinate,
                argument.relativize(jar).toString());
        dependencies.store(argument.resolve(BuildStep.DEPENDENCIES));
    }

    private static void writeSbomJar(Path jar, String sbomLocation, String sbom) throws IOException {
        Files.createDirectories(jar.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Sbom-Location", sbomLocation);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry(sbomLocation));
            out.write(sbom.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private static void writeFileJar(Path jar, String entryName, String content) throws IOException {
        Files.createDirectories(jar.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry(entryName));
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }
}
