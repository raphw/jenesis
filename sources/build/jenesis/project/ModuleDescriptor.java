package build.jenesis.project;

import module java.base;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;

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
        return BuildExecutorModule.PREVIOUS + BuildStep.COMPILE + "/dependencies/" + MultiProjectModule.ARTIFACTS;
    }

    default String runtimeArtifacts() {
        return BuildExecutorModule.PREVIOUS + BuildStep.RUNTIME + "/dependencies/" + MultiProjectModule.ARTIFACTS;
    }

    default String checked() {
        return BuildExecutorModule.PREVIOUS + BuildStep.COMPILE + "/dependencies/" + MultiProjectModule.CHECKED;
    }

    default String runtimeChecked() {
        return BuildExecutorModule.PREVIOUS + BuildStep.RUNTIME + "/dependencies/" + MultiProjectModule.CHECKED;
    }
}
