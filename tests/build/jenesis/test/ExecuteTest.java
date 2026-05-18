package build.jenesis.test;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.Execute;
import build.jenesis.Project;
import build.jenesis.SequencedProperties;
import sample.Sample;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class ExecuteTest {

    @TempDir
    private Path root;

    @AfterEach
    public void clearProperties() {
        System.clearProperty("jenesis.execute.mainClass");
        System.clearProperty("jenesis.execute.module");
    }

    @Test
    public void defaults_carry_no_overrides() {
        Execute execute = new Execute(new Project());
        assertThat(execute.mainClass()).isNull();
        assertThat(execute.module()).isNull();
    }

    @Test
    public void main_class_setter_returns_fresh_instance() {
        Execute original = new Execute(new Project());
        Execute updated = original.mainClass("foo.Bar");
        assertThat(updated.mainClass()).isEqualTo("foo.Bar");
        assertThat(original.mainClass()).isNull();
    }

    @Test
    public void module_setter_returns_fresh_instance() {
        Execute original = new Execute(new Project());
        Execute updated = original.module("sub");
        assertThat(updated.module()).isEqualTo("sub");
        assertThat(original.module()).isNull();
    }

    @Test
    public void resolve_properties_picks_up_main_class() {
        System.setProperty("jenesis.execute.mainClass", "foo.Bar");
        Execute execute = new Execute(new Project()).resolveProperties();
        assertThat(execute.mainClass()).isEqualTo("foo.Bar");
    }

    @Test
    public void resolve_properties_picks_up_module() {
        System.setProperty("jenesis.execute.module", "sub");
        Execute execute = new Execute(new Project()).resolveProperties();
        assertThat(execute.module()).isEqualTo("sub");
    }

    @Test
    public void resolve_properties_keeps_existing_overrides_when_unset() {
        Execute execute = new Execute(new Project())
                .mainClass("a.B")
                .module("sub")
                .resolveProperties();
        assertThat(execute.mainClass()).isEqualTo("a.B");
        assertThat(execute.module()).isEqualTo("sub");
    }

    @Test
    public void execute_aborts_when_no_module_declares_main() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Path alpha = writeManifests("alpha", null, "alpha", null);
        Project.Layout layout = layoutWithModules(Map.of("module-alpha", alpha));
        Project project = new Project().root(root).target(target).layout(layout);
        assertThatThrownBy(() -> new Execute(project).execute())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No module declares a main class");
    }

    @Test
    public void execute_aborts_when_multiple_modules_declare_main() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Path alpha = writeManifests("alpha", "foo.Alpha", "alpha", null);
        Path beta = writeManifests("beta", "foo.Beta", "beta", null);
        Project.Layout layout = layoutWithModules(Map.of(
                "module-alpha", alpha,
                "module-beta", beta));
        Project project = new Project().root(root).target(target).layout(layout);
        assertThatThrownBy(() -> new Execute(project).execute())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Multiple modules declare a main class")
                .hasMessageContaining("foo.Alpha")
                .hasMessageContaining("foo.Beta");
    }

    @Test
    public void execute_aborts_when_explicit_module_has_no_main() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Path alpha = writeManifests("alpha", null, "alpha", null);
        Project.Layout layout = layoutWithModules(Map.of("module-alpha", alpha));
        Project project = new Project().root(root).target(target).layout(layout);
        assertThatThrownBy(() -> new Execute(project).module("alpha").execute())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No module at path: alpha");
    }

    @Test
    public void execute_aborts_when_main_artifact_missing() throws IOException {
        Path target = Files.createDirectory(root.resolve("target"));
        Path alpha = writeManifests("alpha", "foo.Alpha", "alpha", null);
        writeIdentity(alpha, Map.of("alpha/coord", "missing.jar"));
        Project.Layout layout = layoutWithModules(Map.of("module-alpha", alpha));
        Project project = new Project().root(root).target(target).layout(layout);
        assertThatThrownBy(() -> new Execute(project).execute())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Did not find a main artifact");
    }

    @Test
    public void execute_launches_single_declared_main() throws IOException, InterruptedException {
        Path target = Files.createDirectory(root.resolve("target"));
        Path alpha = writeManifests("alpha", Sample.class.getName(), "alpha", null);
        Path classesJar = packageSample(alpha.resolve("classes.jar"));
        writeIdentity(alpha, Map.of("alpha/coord", alpha.relativize(classesJar).toString()));
        Project.Layout layout = layoutWithModules(Map.of("module-alpha", alpha));
        Project project = new Project().root(root).target(target).layout(layout);
        int code = new Execute(project).execute();
        assertThat(code).isEqualTo(0);
    }

    @Test
    public void execute_honours_explicit_main_class_override() throws IOException, InterruptedException {
        Path target = Files.createDirectory(root.resolve("target"));
        Path alpha = writeManifests("alpha", "ignored.OldMain", "alpha", null);
        Path classesJar = packageSample(alpha.resolve("classes.jar"));
        writeIdentity(alpha, Map.of("alpha/coord", alpha.relativize(classesJar).toString()));
        Project.Layout layout = layoutWithModules(Map.of("module-alpha", alpha));
        Project project = new Project().root(root).target(target).layout(layout);
        int code = new Execute(project).mainClass(Sample.class.getName()).execute();
        assertThat(code).isEqualTo(0);
    }

    private Path writeManifests(String name, String main, String path, String module) throws IOException {
        Path folder = Files.createDirectory(root.resolve(name + "-manifests"));
        Properties properties = new SequencedProperties();
        if (path != null) {
            properties.setProperty("path", path);
        }
        if (main != null) {
            properties.setProperty("main", main);
        }
        if (module != null) {
            properties.setProperty("module", module);
        }
        try (Writer writer = Files.newBufferedWriter(folder.resolve("module.properties"))) {
            properties.store(writer, null);
        }
        return folder;
    }

    private void writeIdentity(Path folder, Map<String, String> entries) throws IOException {
        Properties properties = new SequencedProperties();
        entries.forEach(properties::setProperty);
        try (Writer writer = Files.newBufferedWriter(folder.resolve("identity.properties"))) {
            properties.store(writer, null);
        }
    }

    private Path packageSample(Path jar) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(Sample.class.getName().replace('.', '/') + ".class"));
            try (InputStream input = Sample.class.getResourceAsStream(Sample.class.getSimpleName() + ".class")) {
                requireNonNull(input).transferTo(output);
            }
            output.closeEntry();
        }
        return jar;
    }

    private Project.Layout layoutWithModules(Map<String, Path> modules) {
        return (executor, _, _) -> {
            executor.addModule(Project.BUILD, (sub, _) -> {
                for (Map.Entry<String, Path> entry : modules.entrySet()) {
                    sub.addModule(entry.getKey(), (inner, _) -> inner.addSource("manifests", entry.getValue()));
                }
            });
            return name -> Project.BUILD + "/module-" + name;
        };
    }
}
