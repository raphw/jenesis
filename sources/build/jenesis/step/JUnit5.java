package build.jenesis.step;

import module java.base;

public record JUnit5() implements TestEngine {

    public static final String JUPITER_MARKER_CLASS = "org.junit.jupiter.api.Test";
    private static final String DEFAULT_PLATFORM_VERSION = "1.11.4";

    @Override
    public String module() {
        return "org.junit.platform.console";
    }

    @Override
    public Set<String> coordinates() {
        LinkedHashSet<String> coordinates = new LinkedHashSet<>();
        coordinates.add("maven/org.junit.platform/junit-platform-console");
        coordinates.add("module/org.junit.platform.console");
        return coordinates;
    }

    @Override
    public Map<String, String> versions() {
        LinkedHashMap<String, String> versions = new LinkedHashMap<>();
        versions.put("maven/org.junit.platform/junit-platform-console", DEFAULT_PLATFORM_VERSION);
        versions.put("maven/org.junit.platform/junit-platform-commons", DEFAULT_PLATFORM_VERSION);
        versions.put("maven/org.junit.platform/junit-platform-engine", DEFAULT_PLATFORM_VERSION);
        versions.put("maven/org.junit.platform/junit-platform-launcher", DEFAULT_PLATFORM_VERSION);
        versions.put("maven/org.junit.platform/junit-platform-reporting", DEFAULT_PLATFORM_VERSION);
        versions.put("module/org.junit.platform.console", DEFAULT_PLATFORM_VERSION);
        versions.put("module/org.junit.platform.commons", DEFAULT_PLATFORM_VERSION);
        versions.put("module/org.junit.platform.engine", DEFAULT_PLATFORM_VERSION);
        versions.put("module/org.junit.platform.launcher", DEFAULT_PLATFORM_VERSION);
        versions.put("module/org.junit.platform.reporting", DEFAULT_PLATFORM_VERSION);
        return versions;
    }

    @Override
    public String markerClass() {
        return JUPITER_MARKER_CLASS;
    }

    @Override
    public String mainClass() {
        return "org.junit.platform.console.ConsoleLauncher";
    }

    @Override
    public String prefix() {
        return "-select-class=";
    }

    @Override
    public String methodPrefix() {
        return "-select-method=";
    }

    @Override
    public List<String> arguments() {
        return List.of("execute", "--disable-banner", "--disable-ansi-colors");
    }
}
