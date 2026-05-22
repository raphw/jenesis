package build.jenesis.project;

import module java.base;

public interface ModuleDescriptor {

    String name();

    SequencedSet<String> dependencies();

    String sources();

    SequencedSet<String> resources();

    String manifests();

    String coordinates();

    String artifacts(DependencyScope scope);

    String resolved(DependencyScope scope);
}
