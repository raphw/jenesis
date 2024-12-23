package build.buildbuddy;

public enum MavenDependencyScope {

    COMPILE, RUNTIME, PROVIDED, TEST, SYSTEM, IMPORT;

    public boolean implies(MavenDependencyScope scope) {
        return scope == null || ordinal() <= scope.ordinal();
    }
}
