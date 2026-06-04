package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.DependencyTreeReport;
import build.jenesis.ResolutionContext;

import static org.assertj.core.api.Assertions.assertThat;

public class DependencyTreeReportTest {

    private ByteArrayOutputStream bytes;
    private DependencyTreeReport report;

    @BeforeEach
    public void setUp() {
        bytes = new ByteArrayOutputStream();
        report = new DependencyTreeReport(new PrintStream(bytes, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return bytes.toString(StandardCharsets.UTF_8).replaceAll("\\[[0-9;]*m", "");
    }

    @Test
    public void renders_followed_tree_with_connectors_versions_and_scope() {
        report.onDependency("maven", null, "maven/g/a/1.0", "1.0", "compile", true, () -> null);
        report.onDependency("maven", "maven/g/a/1.0", "maven/g/b/2.0", "2.0", "compile", true, () -> null);
        report.onDependency("maven", "maven/g/a/1.0", "maven/g/d/4.0", "4.0", "compile", true, () -> null);
        report.onDependency("maven", "maven/g/b/2.0", "maven/g/c/3.0", "3.0", "runtime", true, () -> null);
        report.onResolved();
        String text = output();
        assertThat(text).contains("Dependency tree:");
        assertThat(text).contains("maven/g/a 1.0 [compile]");
        assertThat(text).contains("├─ maven/g/b 2.0 [compile]");
        assertThat(text).contains("│  └─ maven/g/c 3.0 [runtime]");
        assertThat(text).contains("└─ maven/g/d 4.0 [compile]");
    }

    @Test
    public void marks_not_followed_duplicates_and_does_not_expand_them() {
        report.onDependency("maven", null, "maven/g/a/1.0", "1.0", "compile", true, () -> null);
        report.onDependency("maven", "maven/g/a/1.0", "maven/g/b/2.0", "2.0", "compile", true, () -> null);
        report.onDependency("maven", "maven/g/b/2.0", "maven/g/c/3.0", "3.0", "compile", true, () -> null);
        report.onDependency("maven", "maven/g/a/1.0", "maven/g/c/3.0", "3.0", "compile", false, () -> null);
        report.onResolved();
        String text = output();
        assertThat(text).contains("maven/g/c 3.0 [compile] (*)");
        assertThat(text.split("\\(\\*\\)", -1).length - 1).isEqualTo(1);
    }

    @Test
    public void annotates_the_negotiated_version_when_it_differs_from_the_requested_one() {
        report.onDependency("maven", null, "maven/g/a/[1,2]", "[1,2]", "compile", true, () -> null);
        report.onResolution("maven", "maven/g/a", "2");
        report.onResolved();
        assertThat(output()).contains("maven/g/a [1,2] -> 2");
    }

    @Test
    public void renders_resolution_context_metadata() {
        report.onDependency("module", null, "module/org.foo/1.0", "1.0", null, true,
                () -> new ResolutionContext("org.foo", "1.0", Boolean.TRUE, "maven/org.foo/foo/1.0"));
        report.onResolved();
        String text = output();
        assertThat(text).contains("(module org.foo@1.0, automatic)");
        assertThat(text).contains("=> maven/org.foo/foo/1.0");
    }

    @Test
    public void lists_resolved_dependencies_below_the_tree() {
        report.onDependency("maven", null, "maven/g/a/1.0", "1.0", "compile", true, () -> null);
        report.onResolution("maven", "maven/g/a", "1.0");
        report.onResolved();
        String text = output();
        assertThat(text).contains("Resolved dependencies:");
        assertThat(text).contains("maven/g/a -> 1.0");
    }

    @Test
    public void prints_nothing_when_no_dependencies_were_observed() {
        report.onResolved();
        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEmpty();
    }
}
