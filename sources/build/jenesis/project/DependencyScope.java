package build.jenesis.project;

import module java.base;

public enum DependencyScope {

    COMPILE, RUNTIME;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
