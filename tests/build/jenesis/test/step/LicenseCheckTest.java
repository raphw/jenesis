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

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
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
        String previous = System.getProperty("jenesis.compliance.license.unknown");
        System.clearProperty("jenesis.compliance.license.unknown");
        try {
            assertThatThrownBy(() -> run(new LicenseCheck()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no license");
        } finally {
            restore("jenesis.compliance.license.unknown", previous);
        }
    }

    @Test
    public void unknown_ignore_does_not_fail_on_a_missing_license() throws Exception {
        resolve("org.example/lib/1.2.3");
        assertThat(run(new LicenseCheck().unknown(LicenseCheck.Unknown.IGNORE)).next()).isTrue();
    }

    @Test
    public void reads_the_unknown_policy_from_the_system_property() throws Exception {
        resolve("org.example/lib/1.2.3");
        String previous = System.getProperty("jenesis.compliance.license.unknown");
        System.setProperty("jenesis.compliance.license.unknown", "ignore");
        try {
            assertThat(run(new LicenseCheck()).next()).isTrue();
        } finally {
            restore("jenesis.compliance.license.unknown", previous);
        }
    }

    @Test
    public void an_override_supplies_a_license_for_a_coordinate() throws Exception {
        resolve("org.example/lib/1.2.3");
        assertThat(run(new LicenseCheck().overrides(Map.of("org.example/lib", "Apache-2.0"))).next()).isTrue();
        assertThat(report()).content().contains("[OK]").contains("Apache-2.0");
    }

    @Test
    public void reads_the_allow_list_from_the_system_property() throws Exception {
        resolve("org.example/lib/1.2.3", "Eclipse Public License 2.0");
        String previous = System.getProperty("jenesis.compliance.license");
        System.setProperty("jenesis.compliance.license", "Apache, MIT");
        try {
            assertThatThrownBy(() -> run(new LicenseCheck())).isInstanceOf(IllegalStateException.class);
        } finally {
            restore("jenesis.compliance.license", previous);
        }
    }
}
