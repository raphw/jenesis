package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutorModule;

public interface ModuleDescriptor {

    String name();

    SequencedSet<String> dependencies();

    default String sources() {
        return BuildExecutorModule.PREVIOUS + MultiProjectModule.SOURCES;
    }

    default String manifests() {
        return BuildExecutorModule.PREVIOUS + MultiProjectModule.MANIFESTS;
    }

    default String artifacts() {
        return BuildExecutorModule.PREVIOUS + MultiProjectModule.COMPILE + "/dependencies/" + MultiProjectModule.ARTIFACTS;
    }

    default String runtimeArtifacts() {
        return BuildExecutorModule.PREVIOUS + MultiProjectModule.RUNTIME + "/dependencies/" + MultiProjectModule.ARTIFACTS;
    }

    default String checked() {
        return BuildExecutorModule.PREVIOUS + MultiProjectModule.COMPILE + "/dependencies/" + MultiProjectModule.CHECKED;
    }

    default String runtimeChecked() {
        return BuildExecutorModule.PREVIOUS + MultiProjectModule.RUNTIME + "/dependencies/" + MultiProjectModule.CHECKED;
    }
}
