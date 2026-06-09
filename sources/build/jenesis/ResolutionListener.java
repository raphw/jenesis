package build.jenesis;

import module java.base;

@FunctionalInterface
public interface ResolutionListener {

    void onDependency(String prefix,
                      String parent,
                      String coordinate,
                      String version,
                      String scope,
                      boolean followed,
                      Supplier<ResolutionContext> context);

    default void onResolution(String prefix,
                              String coordinate,
                              String version) {
    }

    default void onLicenses(String prefix,
                            String coordinate,
                            String version,
                            List<License> licenses) {
    }

    default void onResolved() {
    }
}
