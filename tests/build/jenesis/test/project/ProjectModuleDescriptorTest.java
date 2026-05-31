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
        ModuleDescriptor base = new MavenModuleDescriptor("module-foo", Collections.emptyNavigableSet(), Collections.emptyNavigableSet());
        ProjectModuleDescriptor descriptor = new ProjectModuleDescriptor(base, true, false, true, false);
        assertThat(descriptor.test()).isTrue();
        assertThat(descriptor.source()).isFalse();
        assertThat(descriptor.documentation()).isTrue();
    }

    @Test
    public void delegates_module_descriptor_accessors_to_base() {
        LinkedHashSet<String> dependencies = new LinkedHashSet<>(List.of("module-bar"));
        ModuleDescriptor base = new MavenModuleDescriptor("module-foo", dependencies, Collections.emptyNavigableSet());
        ProjectModuleDescriptor descriptor = new ProjectModuleDescriptor(base, false, false, false, false);
        assertThat(descriptor.name()).isEqualTo(base.name());
        assertThat(descriptor.dependencies()).isEqualTo(base.dependencies());
        assertThat(descriptor.sources()).isEqualTo(base.sources());
        assertThat(descriptor.resources()).isEqualTo(base.resources());
        assertThat(descriptor.manifests()).isEqualTo(base.manifests());
        assertThat(descriptor.artifacts(DependencyScope.COMPILE)).isEqualTo(base.artifacts(DependencyScope.COMPILE));
        assertThat(descriptor.artifacts(DependencyScope.RUNTIME)).isEqualTo(base.artifacts(DependencyScope.RUNTIME));
        assertThat(descriptor.resolved(DependencyScope.COMPILE)).isEqualTo(base.resolved(DependencyScope.COMPILE));
        assertThat(descriptor.resolved(DependencyScope.RUNTIME)).isEqualTo(base.resolved(DependencyScope.RUNTIME));
    }

    @Test
    public void to_inherited_prepends_one_parent_segment_per_call() {
        ModuleDescriptor base = new MavenModuleDescriptor("module-foo", Collections.emptyNavigableSet(), Collections.emptyNavigableSet());
        ProjectModuleDescriptor descriptor = new ProjectModuleDescriptor(base, true, true, true, false);
        ProjectModuleDescriptor inherited = descriptor.toInherited();
        assertThat(inherited.name()).isEqualTo(base.name());
        assertThat(inherited.dependencies()).isEqualTo(base.dependencies());
        assertThat(inherited.test()).isTrue();
        assertThat(inherited.source()).isTrue();
        assertThat(inherited.documentation()).isTrue();
        assertThat(inherited.sources()).isEqualTo(prefixed(base.sources(), "../"));
        assertThat(inherited.resources()).isEqualTo(prefixed(base.resources(), "../"));
        assertThat(inherited.manifests()).isEqualTo(prefixed(base.manifests(), "../"));
        assertThat(inherited.artifacts(DependencyScope.COMPILE)).isEqualTo(prefixed(base.artifacts(DependencyScope.COMPILE), "../"));
        assertThat(inherited.artifacts(DependencyScope.RUNTIME)).isEqualTo(prefixed(base.artifacts(DependencyScope.RUNTIME), "../"));
        assertThat(inherited.resolved(DependencyScope.COMPILE)).isEqualTo(prefixed(base.resolved(DependencyScope.COMPILE), "../"));
        assertThat(inherited.resolved(DependencyScope.RUNTIME)).isEqualTo(prefixed(base.resolved(DependencyScope.RUNTIME), "../"));
        ProjectModuleDescriptor twice = inherited.toInherited();
        assertThat(twice.sources()).isEqualTo(prefixed(base.sources(), "../../"));
        assertThat(twice.manifests()).isEqualTo(prefixed(base.manifests(), "../../"));
        assertThat(twice.artifacts(DependencyScope.COMPILE)).isEqualTo(prefixed(base.artifacts(DependencyScope.COMPILE), "../../"));
        assertThat(twice.resolved(DependencyScope.RUNTIME)).isEqualTo(prefixed(base.resolved(DependencyScope.RUNTIME), "../../"));
    }

    private static SequencedSet<String> prefixed(SequencedSet<String> values, String prefix) {
        LinkedHashSet<String> prefixed = new LinkedHashSet<>();
        for (String value : values) {
            prefixed.add(prefix + value);
        }
        return prefixed;
    }
}
