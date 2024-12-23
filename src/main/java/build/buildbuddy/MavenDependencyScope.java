package build.buildbuddy;

public enum MavenDependencyScope {

    COMPILE, PROVIDED, RUNTIME, TEST, SYSTEM, IMPORT;

    static MavenDependencyScope parse(String scope) {
        if (!scope.toLowerCase().endsWith(scope.toLowerCase())) {
            throw new IllegalArgumentException("Invalid scope " + scope);
        }
        return MavenDependencyScope.valueOf(scope.toUpperCase());
    }
}
