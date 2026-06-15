package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutorModule;

public final class AssemblyDescriptor {

    private final BuildExecutorModule build;
    private final SequencedMap<String, BuildExecutorModule> tail;

    public AssemblyDescriptor(BuildExecutorModule build) {
        this(build, Collections.emptyNavigableMap());
    }

    private AssemblyDescriptor(BuildExecutorModule build, SequencedMap<String, BuildExecutorModule> tail) {
        this.build = build;
        this.tail = tail;
    }

    public AssemblyDescriptor then(String name, BuildExecutorModule phase) {
        if (tail.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate assembly phase: " + name);
        }
        SequencedMap<String, BuildExecutorModule> next = new LinkedHashMap<>(tail);
        next.put(name, phase);
        return new AssemblyDescriptor(build, next);
    }

    public AssemblyDescriptor mapBuild(UnaryOperator<BuildExecutorModule> operator) {
        return new AssemblyDescriptor(operator.apply(build), tail);
    }

    public BuildExecutorModule build() {
        return build;
    }

    public SequencedMap<String, BuildExecutorModule> tail() {
        return tail;
    }
}
