package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.maven.MavenProject.MavenModuleDescriptor;
import build.jenesis.project.JavaMultiProjectAssembler;
import build.jenesis.project.ModuleDescriptor;
import build.jenesis.project.ProjectModuleDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

public class JavaMultiProjectAssemblerTest {

    @Test
    public void applies_to_descriptor_with_minimal_flags() {
        ModuleDescriptor base = new MavenModuleDescriptor("module-foo", new LinkedHashSet<>());
        ProjectModuleDescriptor descriptor = new ProjectModuleDescriptor(base, false, false, false);
        assertThat(new JavaMultiProjectAssembler().apply(descriptor, Map.of(), Map.of())).isNotNull();
    }

    @Test
    public void applies_to_descriptor_with_tests_enabled() {
        ModuleDescriptor base = new MavenModuleDescriptor("module-foo", new LinkedHashSet<>());
        ProjectModuleDescriptor descriptor = new ProjectModuleDescriptor(base, true, false, false);
        assertThat(new JavaMultiProjectAssembler().apply(descriptor, Map.of(), Map.of())).isNotNull();
    }

    @Test
    public void applies_to_descriptor_with_source_jar_enabled() {
        ModuleDescriptor base = new MavenModuleDescriptor("module-foo", new LinkedHashSet<>());
        ProjectModuleDescriptor descriptor = new ProjectModuleDescriptor(base, false, true, false);
        assertThat(new JavaMultiProjectAssembler().apply(descriptor, Map.of(), Map.of())).isNotNull();
    }

    @Test
    public void applies_to_descriptor_with_javadoc_enabled() {
        ModuleDescriptor base = new MavenModuleDescriptor("module-foo", new LinkedHashSet<>());
        ProjectModuleDescriptor descriptor = new ProjectModuleDescriptor(base, false, false, true);
        assertThat(new JavaMultiProjectAssembler().apply(descriptor, Map.of(), Map.of())).isNotNull();
    }
}
