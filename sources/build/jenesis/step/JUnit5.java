package build.jenesis.step;

import module java.base;

public record JUnit5(String jupiterVersion, String platformVersion) implements TestEngine {

    public static final String JUPITER_MARKER_CLASS = "org.junit.jupiter.api.Test";
    public static final String PLATFORM_MARKER_CLASS = "org.junit.platform.commons.JUnitException";

    @Override
    public String module() {
        return "org.junit.platform.console";
    }

    @Override
    public Set<String> coordinates() {
        LinkedHashSet<String> coordinates = new LinkedHashSet<>();
        coordinates.add("maven/org.junit.platform/junit-platform-console/" + platformVersion);
        coordinates.add("module/org.junit.platform.console");
        return coordinates;
    }

    @Override
    public Map<String, String> versions() {
        LinkedHashMap<String, String> versions = new LinkedHashMap<>();
        versions.put("module/org.junit.platform.console", platformVersion);
        versions.put("module/org.junit.platform.commons", platformVersion);
        versions.put("module/org.junit.platform.engine", platformVersion);
        versions.put("module/org.junit.platform.launcher", platformVersion);
        versions.put("module/org.junit.platform.reporting", platformVersion);
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
