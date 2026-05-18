package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutorModule;

public class ProjectModuleDescriptor implements ModuleDescriptor {

    private final ModuleDescriptor base;
    private final boolean tests;
    private final boolean source;
    private final boolean javadoc;
    private final int depth;

    public ProjectModuleDescriptor(ModuleDescriptor base, boolean tests, boolean source, boolean javadoc) {
        this(base, tests, source, javadoc, 0);
    }

    private ProjectModuleDescriptor(ModuleDescriptor base, boolean tests, boolean source, boolean javadoc, int depth) {
        this.base = base;
        this.tests = tests;
        this.source = source;
        this.javadoc = javadoc;
        this.depth = depth;
    }

    public ProjectModuleDescriptor toInherited() {
        return new ProjectModuleDescriptor(base, tests, source, javadoc, depth + 1);
    }

    public boolean tests() {
        return tests;
    }

    public boolean source() {
        return source;
    }

    public boolean javadoc() {
        return javadoc;
    }

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
        return BuildExecutorModule.PREVIOUS.repeat(depth) + base.sources();
    }

    @Override
    public String manifests() {
        return BuildExecutorModule.PREVIOUS.repeat(depth) + base.manifests();
    }

    @Override
    public String artifacts(DependencyScope scope) {
        return BuildExecutorModule.PREVIOUS.repeat(depth) + base.artifacts(scope);
    }

    @Override
    public String resolved(DependencyScope scope) {
        return BuildExecutorModule.PREVIOUS.repeat(depth) + base.resolved(scope);
    }
}
