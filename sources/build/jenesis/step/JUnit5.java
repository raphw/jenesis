package build.jenesis.step;

import module java.base;

public record JUnit5() implements TestEngine {

    @Override
    public String module() {
        return "org.junit.platform.console";
    }

    @Override
    public Map<String, String> markers() {
        return Map.of("Implementation-Title", "junit-jupiter-api");
    }

    @Override
    public Map<String, String> runnerMarkers() {
        return Map.of("Implementation-Title", "junit-platform-console");
    }

    @Override
    public Set<String> coordinates() {
        SequencedSet<String> coordinates = new LinkedHashSet<>();
        coordinates.add("module/org.junit.platform.console");
        coordinates.add("maven/org.junit.platform/junit-platform-console/1.11.4");
        return coordinates;
    }

    @Override
    public String mainClass() {
        return "org.junit.platform.console.ConsoleLauncher";
    }

    @Override
    public String classPrefix() {
        return "-select-class=";
    }

    @Override
    public String methodPrefix() {
        return "-select-method=";
    }

    @Override
    public Map<String, String> properties() {
        return Map.of("org.jline.terminal.dumb", "true");
    }

    @Override
    public List<String> arguments() {
        return List.of("execute", "--disable-banner", "--disable-ansi-colors");
    }
}
