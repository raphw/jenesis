package build.jenesis;

public record ResolutionContext(String moduleName,
                                String moduleVersion,
                                Boolean automaticModule,
                                String resolvedCoordinate) {
}
