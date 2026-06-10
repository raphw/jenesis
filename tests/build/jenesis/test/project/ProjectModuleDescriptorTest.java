package build.jenesis.test.project;

import module java.base;
import module org.junit.jupiter.api;
import build.jenesis.PathPlacement;
import build.jenesis.maven.MavenProject.MavenModuleDescriptor;
import build.jenesis.project.ProjectModule;
import build.jenesis.project.ProjectModuleDescriptor;

import static org.assertj.core.api.Assertions.assertThat;

public class ProjectModuleDescriptorTest {

    @Test
    public void carries_the_flags_unchanged() {
        ProjectModule base = new MavenModuleDescriptor("module-foo", Collections.emptyNavigableSet(), Collections.emptyNavigableSet());
        ProjectModuleDescriptor descriptor = new ProjectModuleDescriptor(base, Path.of("."), true, false, true, null, PathPlacement.INFERRED);
        assertThat(descriptor.test()).isTrue();
        assertThat(descriptor.source()).isFalse();
        assertThat(descriptor.documentation()).isTrue();
    }

    @Test
    public void delegates_module_descriptor_accessors_to_base() {
        LinkedHashSet<String> dependencies = new LinkedHashSet<>(List.of("module-bar"));
        ProjectModule base = new MavenModuleDescriptor("module-foo", dependencies, Collections.emptyNavigableSet());
        ProjectModuleDescriptor descriptor = new ProjectModuleDescriptor(base, Path.of("."), false, false, false, null, PathPlacement.INFERRED);
        assertThat(descriptor.name()).isEqualTo(base.name());
        assertThat(descriptor.dependencies()).isEqualTo(base.dependencies());
        assertThat(descriptor.sources()).isEqualTo(base.sources());
        assertThat(descriptor.resources()).isEqualTo(base.resources());
        assertThat(descriptor.manifests()).isEqualTo(base.manifests());
        assertThat(descriptor.artifacts()).isEqualTo(base.artifacts());
    }

    @Test
    public void to_inherited_prepends_one_parent_segment_per_call() {
        ProjectModule base = new MavenModuleDescriptor("module-foo", Collections.emptyNavigableSet(), Collections.emptyNavigableSet());
        ProjectModuleDescriptor descriptor = new ProjectModuleDescriptor(base, Path.of("."), true, true, true, null, PathPlacement.INFERRED);
        ProjectModuleDescriptor inherited = descriptor.toInherited();
        assertThat(inherited.name()).isEqualTo(base.name());
        assertThat(inherited.dependencies()).isEqualTo(base.dependencies());
        assertThat(inherited.test()).isTrue();
        assertThat(inherited.source()).isTrue();
        assertThat(inherited.documentation()).isTrue();
        assertThat(inherited.sources()).isEqualTo(prefixed(base.sources(), "../"));
        assertThat(inherited.resources()).isEqualTo(prefixed(base.resources(), "../"));
        assertThat(inherited.manifests()).isEqualTo(prefixed(base.manifests(), "../"));
        assertThat(inherited.artifacts()).isEqualTo(prefixed(base.artifacts(), "../"));
        ProjectModuleDescriptor twice = inherited.toInherited();
        assertThat(twice.sources()).isEqualTo(prefixed(base.sources(), "../../"));
        assertThat(twice.manifests()).isEqualTo(prefixed(base.manifests(), "../../"));
        assertThat(twice.artifacts()).isEqualTo(prefixed(base.artifacts(), "../../"));
    }

    private static SequencedSet<String> prefixed(SequencedSet<String> values, String prefix) {
        LinkedHashSet<String> prefixed = new LinkedHashSet<>();
        for (String value : values) {
            prefixed.add(prefix + value);
        }
        return prefixed;
    }
}
