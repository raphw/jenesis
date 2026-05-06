package build.jenesis.module;

import build.jenesis.BuildExecutorModule;
import build.jenesis.project.ModuleDescriptor;
import build.jenesis.project.MultiProjectModule;

import module java.base;

public record ModularModuleDescriptor(String name, SequencedSet<String> dependencies) implements ModuleDescriptor {

    public String sources() {
        return BuildExecutorModule.PREVIOUS + MultiProjectModule.SOURCES;
    }

    public String manifests() {
        return BuildExecutorModule.PREVIOUS + MultiProjectModule.MANIFESTS;
    }

    public String artifacts() {
        return BuildExecutorModule.PREVIOUS + MultiProjectModule.ARTIFACTS;
    }

    public String checked() {
        return BuildExecutorModule.PREVIOUS + MultiProjectModule.CHECKED;
    }
}
