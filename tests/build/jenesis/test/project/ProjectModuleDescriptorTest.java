package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.maven.MavenProject.MavenModuleDescriptor;
import build.jenesis.project.DependencyScope;
import build.jenesis.project.ModuleDescriptor;
import build.jenesis.project.ProjectModuleDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

public class ProjectModuleDescriptorTest {

    @Test
    public void carries_the_flags_unchanged() {
        ModuleDescriptor base = new MavenModuleDescriptor("module-foo", new LinkedHashSet<>());
        ProjectModuleDescriptor descriptor = new ProjectModuleDescriptor(base, true, false, true);
        assertThat(descriptor.tests()).isTrue();
        assertThat(descriptor.source()).isFalse();
        assertThat(descriptor.javadoc()).isTrue();
    }

    @Test
    public void delegates_module_descriptor_accessors_to_base() {
        LinkedHashSet<String> dependencies = new LinkedHashSet<>(List.of("module-bar"));
        ModuleDescriptor base = new MavenModuleDescriptor("module-foo", dependencies);
        ProjectModuleDescriptor descriptor = new ProjectModuleDescriptor(base, false, false, false);
        assertThat(descriptor.name()).isEqualTo(base.name());
        assertThat(descriptor.dependencies()).isEqualTo(base.dependencies());
        assertThat(descriptor.sources()).isEqualTo(base.sources());
        assertThat(descriptor.manifests()).isEqualTo(base.manifests());
        assertThat(descriptor.artifacts(DependencyScope.COMPILE)).isEqualTo(base.artifacts(DependencyScope.COMPILE));
        assertThat(descriptor.artifacts(DependencyScope.RUNTIME)).isEqualTo(base.artifacts(DependencyScope.RUNTIME));
        assertThat(descriptor.resolved(DependencyScope.COMPILE)).isEqualTo(base.resolved(DependencyScope.COMPILE));
        assertThat(descriptor.resolved(DependencyScope.RUNTIME)).isEqualTo(base.resolved(DependencyScope.RUNTIME));
    }
}
