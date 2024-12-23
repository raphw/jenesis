package build.buildbuddy;

public enum MavenDependencyScope {

    COMPILE, RUNTIME, PROVIDED, TEST, SYSTEM, IMPORT;

    public boolean implies(MavenDependencyScope scope) {
        return ordinal() <= scope.ordinal();
    }

    public MavenDependencyScope merge(MavenDependencyScope scope) {
        return switch (this) {
            case null -> scope == MavenDependencyScope.IMPORT ? null : scope;
            case COMPILE -> switch (scope) {
                case COMPILE, RUNTIME -> scope;
                default -> null;
            };
            case PROVIDED, RUNTIME, TEST -> switch (scope) {
                case COMPILE, RUNTIME -> this;
                default -> null;
            };
            case SYSTEM, IMPORT -> null;
        };
    }
}
