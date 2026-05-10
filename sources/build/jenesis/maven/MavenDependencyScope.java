package build.jenesis.maven;

public enum MavenDependencyScope {

    COMPILE, RUNTIME, PROVIDED, TEST, SYSTEM, IMPORT;

    boolean reduces(MavenDependencyScope scope) {
        return scope != null && ordinal() > scope.ordinal();
    }

    static MavenDependencyScope of(String scope) {
        return switch (scope) {
            case "compile" -> COMPILE;
            case "provided" -> PROVIDED;
            case "runtime" -> RUNTIME;
            case "test" -> TEST;
            case "system" -> SYSTEM;
            case "import" -> IMPORT;
            case null -> COMPILE;
            default -> throw new IllegalArgumentException("");
        };
    }
}
