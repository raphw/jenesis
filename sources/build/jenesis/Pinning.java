package build.jenesis;

import module java.base;

public enum Pinning {

    STRICT,

    VERSIONS,

    IGNORE;

    public static Pinning fromProperty() {
        String property = System.getProperty("jenesis.dependency.pin");
        return property == null ? null : Pinning.valueOf(property.toUpperCase(Locale.ROOT));
    }
}
