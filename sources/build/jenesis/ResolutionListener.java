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

    default void onResolved() {
    }
}
