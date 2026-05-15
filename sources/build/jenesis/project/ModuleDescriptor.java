package build.jenesis.project;

import module java.base;

public interface ModuleDescriptor {

    String name();

    SequencedSet<String> dependencies();

    String sources();

    String manifests();

    String artifacts(Scope scope);

    String resolved(Scope scope);
}
