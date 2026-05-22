package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutorModule;

public class ProjectModuleDescriptor implements ModuleDescriptor {

    private final ModuleDescriptor base;
    private final boolean test;
    private final boolean source;
    private final boolean documentation;
    private final boolean strictPinning;
    private final int depth;

    public ProjectModuleDescriptor(ModuleDescriptor base,
                                   boolean test,
                                   boolean source,
                                   boolean documentation,
                                   boolean strictPinning) {
        this(base, test, source, documentation, strictPinning, 0);
    }

    private ProjectModuleDescriptor(ModuleDescriptor base,
                                    boolean test,
                                    boolean source,
                                    boolean documentation,
                                    boolean strictPinning,
                                    int depth) {
        this.base = base;
        this.test = test;
        this.source = source;
        this.documentation = documentation;
        this.strictPinning = strictPinning;
        this.depth = depth;
    }

    public ProjectModuleDescriptor toInherited() {
        return new ProjectModuleDescriptor(base, test, source, documentation, strictPinning, depth + 1);
    }

    public boolean test() {
        return test;
    }

    public boolean source() {
        return source;
    }

    public boolean documentation() {
        return documentation;
    }

    public boolean strictPinning() {
        return strictPinning;
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
    public SequencedSet<String> resources() {
        return base.resources().stream()
                .map(resource -> BuildExecutorModule.PREVIOUS.repeat(depth) + resource)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public String manifests() {
        return BuildExecutorModule.PREVIOUS.repeat(depth) + base.manifests();
    }

    @Override
    public String coordinates() {
        return BuildExecutorModule.PREVIOUS.repeat(depth) + base.coordinates();
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
