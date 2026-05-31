package build.jenesis.project;

import module java.base;

public interface ModuleDescriptor {

    String name();

    SequencedSet<String> dependencies();

    SequencedSet<String> sources();

    SequencedSet<String> resources();

    SequencedSet<String> manifests();

    SequencedSet<String> coordinates();

    SequencedSet<String> artifacts(DependencyScope scope);

    SequencedSet<String> resolved(DependencyScope scope);
}
