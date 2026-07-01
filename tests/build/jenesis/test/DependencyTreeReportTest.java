package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.DependencyTreeReport;
import build.jenesis.License;
import build.jenesis.Resolver;

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
        return bytes.toString(StandardCharsets.UTF_8).replaceAll("\033\\[[0-9;]*m", "");
    }

    private static Resolver.Resolution resolution(List<Resolver.Edge> edges,
                                                  SequencedMap<String, Resolver.Vertex> vertices) {
        return new Resolver.Resolution(new LinkedHashMap<>(), edges, vertices);
    }

    @Test
    public void renders_followed_tree_with_connectors_versions_and_scope() {
        report.render(resolution(List.of(
                new Resolver.Edge(null, "maven/g/a/1.0", "1.0", "compile", true),
                new Resolver.Edge("maven/g/a/1.0", "maven/g/b/2.0", "2.0", "compile", true),
                new Resolver.Edge("maven/g/a/1.0", "maven/g/d/4.0", "4.0", "compile", true),
                new Resolver.Edge("maven/g/b/2.0", "maven/g/c/3.0", "3.0", "runtime", true)),
                new LinkedHashMap<>()));
        String text = output();
        assertThat(text).contains("Dependency tree:");
        assertThat(text).contains("maven/g/a 1.0 [compile]");
        assertThat(text).contains("├─ maven/g/b 2.0 [compile]");
        assertThat(text).contains("│  └─ maven/g/c 3.0 [runtime]");
        assertThat(text).contains("└─ maven/g/d 4.0 [compile]");
    }

    @Test
    public void marks_not_followed_duplicates_and_does_not_expand_them() {
        report.render(resolution(List.of(
                new Resolver.Edge(null, "maven/g/a/1.0", "1.0", "compile", true),
                new Resolver.Edge("maven/g/a/1.0", "maven/g/b/2.0", "2.0", "compile", true),
                new Resolver.Edge("maven/g/b/2.0", "maven/g/c/3.0", "3.0", "compile", true),
                new Resolver.Edge("maven/g/a/1.0", "maven/g/c/3.0", "3.0", "compile", false)),
                new LinkedHashMap<>()));
        String text = output();
        assertThat(text).contains("maven/g/c 3.0 [compile] (*)");
        assertThat(text.split("\\(\\*\\)", -1).length - 1).isEqualTo(1);
    }

    @Test
    public void annotates_the_negotiated_version_when_it_differs_from_the_requested_one() {
        SequencedMap<String, Resolver.Vertex> vertices = new LinkedHashMap<>();
        vertices.put("maven/g/a", new Resolver.Vertex("2", null, false, List.of()));
        report.render(resolution(List.of(
                new Resolver.Edge(null, "maven/g/a/[1,2]", "[1,2]", "compile", true)),
                vertices));
        assertThat(output()).contains("maven/g/a [1,2] -> 2");
    }

    @Test
    public void renders_module_metadata() {
        SequencedMap<String, Resolver.Vertex> vertices = new LinkedHashMap<>();
        vertices.put("module/org.foo", new Resolver.Vertex("1.0", "org.foo", true, List.of()));
        report.render(resolution(List.of(
                new Resolver.Edge(null, "module/org.foo/1.0", "1.0", null, true)),
                vertices));
        assertThat(output()).contains("(module org.foo, automatic)");
    }

    @Test
    public void lists_resolved_dependencies_below_the_tree() {
        SequencedMap<String, Resolver.Vertex> vertices = new LinkedHashMap<>();
        vertices.put("maven/g/a", new Resolver.Vertex("1.0", null, false, List.of()));
        report.render(resolution(List.of(
                new Resolver.Edge(null, "maven/g/a/1.0", "1.0", "compile", true)),
                vertices));
        String text = output();
        assertThat(text).contains("Resolved dependencies:");
        assertThat(text).contains("maven/g/a -> 1.0");
    }

    @Test
    public void prints_nothing_when_no_dependencies_were_observed() {
        report.render(resolution(List.of(), new LinkedHashMap<>()));
        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    public void summary_aggregates_licenses_permissiveness_and_module_kinds() {
        SequencedMap<String, Resolver.Vertex> vertices = new LinkedHashMap<>();
        vertices.put("maven/g/a", new Resolver.Vertex("1.0", "g.a", false,
                List.of(new License("Apache-2.0", "permissive", "Apache License 2.0", null))));
        vertices.put("maven/g/b", new Resolver.Vertex("1.0", "g.b", true,
                List.of(new License("Apache-2.0", "permissive", "Apache License 2.0", null))));
        vertices.put("maven/g/c", new Resolver.Vertex("1.0", null, false,
                List.of(new License("GPL-3.0-only", "strong-copyleft", "GNU GPL v3", null))));
        vertices.put("maven/g/d", new Resolver.Vertex("1.0", null, false, List.of()));
        report.summary(vertices);
        String text = output();
        assertThat(text).contains("Licenses:");
        assertThat(text).contains("Apache-2.0");
        assertThat(text).contains("GPL-3.0-only");
        assertThat(text).contains("unknown");
        assertThat(text).contains("Permissiveness:");
        assertThat(text).contains("permissive");
        assertThat(text).contains("strong-copyleft");
        assertThat(text).contains("Modules:");
        assertThat(text).contains("named");
        assertThat(text).contains("automatic");
        assertThat(text).contains("non-modular");
        assertThat(text).contains("2 ( 50%)");
        assertThat(text).contains("2 licenses implied");
    }

    @Test
    public void summary_picks_the_most_permissive_license_and_counts_multi_license_dependencies() {
        SequencedMap<String, Resolver.Vertex> vertices = new LinkedHashMap<>();
        vertices.put("maven/g/a", new Resolver.Vertex("1.0", null, false, List.of(
                new License("GPL-3.0-only", "strong-copyleft", "GNU GPL v3", null),
                new License("Apache-2.0", "permissive", "Apache License 2.0", null))));
        vertices.put("maven/g/b", new Resolver.Vertex("1.0", null, false, List.of(
                new License("MIT", "permissive", "MIT License", null))));
        report.summary(vertices);
        String text = output();
        assertThat(text).contains("Apache-2.0");
        assertThat(text).doesNotContain("GPL-3.0-only");
        assertThat(text).contains("3 licenses implied");
        assertThat(text).contains("1 dependency offers multiple");
    }

    @Test
    public void summary_prints_nothing_without_dependencies() {
        report.summary(new LinkedHashMap<>());
        assertThat(bytes.toString(StandardCharsets.UTF_8)).isEmpty();
    }
}
