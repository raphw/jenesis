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
        SequencedProperties dependencies = new SequencedProperties();
        dependencies.setProperty("main/compile/maven/" + coordinate, "resolved/lib.jar");
        dependencies.store(argument.resolve(BuildStep.DEPENDENCIES));
        SequencedProperties licenses = new SequencedProperties();
        for (int index = 0; index < licenseNames.length; index++) {
            licenses.setProperty("maven/" + coordinate + "#" + index + "#name", licenseNames[index]);
        }
        licenses.store(argument.resolve("licenses.properties"));
    }

    private Path report() {
        return next.resolve("reports").resolve("compliance").resolve("licenses.txt");
    }

    @Test
    public void fails_on_a_denied_license() throws Exception {
        resolve("org.example/lib/1.2.3", "GNU General Public License v3.0");
        assertThatThrownBy(() -> run(new LicenseCheck().denied(new LinkedHashSet<>(List.of("General Public License")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org.example/lib/1.2.3");
    }

    @Test
    public void passes_when_a_license_is_on_the_allow_list() throws Exception {
        resolve("org.example/lib/1.2.3", "Apache License, Version 2.0");
        BuildStepResult result = run(new LicenseCheck().allowed(new LinkedHashSet<>(List.of("Apache", "MIT"))));
        assertThat(result.next()).isTrue();
        assertThat(report()).content().contains("org.example/lib/1.2.3 [OK]");
    }

    @Test
    public void fails_when_no_license_is_on_the_allow_list() throws Exception {
        resolve("org.example/lib/1.2.3", "Eclipse Public License 2.0");
        assertThatThrownBy(() -> run(new LicenseCheck().allowed(new LinkedHashSet<>(List.of("Apache")))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    public void reports_without_failing_when_no_policy_is_configured() throws Exception {
        resolve("org.example/lib/1.2.3", "Apache-2.0");
        BuildStepResult result = run(new LicenseCheck());
        assertThat(result.next()).isTrue();
        assertThat(report()).content().contains("org.example/lib/1.2.3");
    }

    @Test
    public void fails_on_a_missing_license_when_configured() throws Exception {
        resolve("org.example/lib/1.2.3");
        assertThatThrownBy(() -> run(new LicenseCheck().failOnMissing(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no license");
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

    @Test
    public void is_off_by_default_without_the_property() throws Exception {
        resolve("org.example/lib/1.2.3", "Eclipse Public License 2.0");
        String previous = System.getProperty("jenesis.compliance.license");
        System.clearProperty("jenesis.compliance.license");
        try {
            assertThat(run(new LicenseCheck()).next()).isTrue();
        } finally {
            restore("jenesis.compliance.license", previous);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
