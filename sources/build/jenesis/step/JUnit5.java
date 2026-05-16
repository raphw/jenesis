package build.jenesis.step;

import module java.base;

public record JUnit5() implements TestEngine {

    public static final String JUPITER_MARKER_CLASS = "org.junit.jupiter.api.Test";

    @Override
    public String module() {
        return "org.junit.platform.console";
    }

    @Override
    public Set<String> coordinates() {
        LinkedHashSet<String> coordinates = new LinkedHashSet<>();
        coordinates.add("maven/org.junit.platform/junit-platform-console/1.11.4");
        coordinates.add("module/org.junit.platform.console");
        return coordinates;
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
