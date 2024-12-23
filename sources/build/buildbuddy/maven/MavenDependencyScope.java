package build.buildbuddy.maven;

public enum MavenDependencyScope {

    COMPILE, RUNTIME, PROVIDED, TEST, SYSTEM, IMPORT;

    boolean reduces(MavenDependencyScope scope) {
        return scope != null && ordinal() > scope.ordinal();
    }
}
