package build.jenesis.project;

import module java.base;

public record ProjectModuleDescriptor(ModuleDescriptor base,
                                      boolean tests,
                                      boolean source,
                                      boolean javadoc) implements ModuleDescriptor {

    @Override
    public String name() {
        return base.name();
    }

    @Override
    public SequencedSet<String> dependencies() {
        return base.dependencies();
    }

    @Override
    public String sources() {
        return base.sources();
    }

    @Override
    public String manifests() {
        return base.manifests();
    }

    @Override
    public String artifacts(DependencyScope scope) {
        return base.artifacts(scope);
    }

    @Override
    public String resolved(DependencyScope scope) {
        return base.resolved(scope);
    }
}
