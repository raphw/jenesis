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
        return BuildExecutorModule.PREVIOUS + MultiProjectModule.COMPILE + "/dependencies/" + MultiProjectModule.ARTIFACTS;
    }

    public String runtimeArtifacts() {
        return BuildExecutorModule.PREVIOUS + MultiProjectModule.RUNTIME + "/dependencies/" + MultiProjectModule.ARTIFACTS;
    }

    public String checked() {
        return BuildExecutorModule.PREVIOUS + MultiProjectModule.COMPILE + "/dependencies/" + MultiProjectModule.CHECKED;
    }

    public String runtimeChecked() {
        return BuildExecutorModule.PREVIOUS + MultiProjectModule.RUNTIME + "/dependencies/" + MultiProjectModule.CHECKED;
    }
}
