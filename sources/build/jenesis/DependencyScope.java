package build.jenesis;

import module java.base;

public enum DependencyScope {

    COMPILE, RUNTIME, PLUGIN;

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    public DependencyScope resolution() {
        return this == PLUGIN ? RUNTIME : this;
    }
}
