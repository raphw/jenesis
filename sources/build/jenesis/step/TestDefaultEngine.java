package build.jenesis.step;

import module java.base;

public enum TestDefaultEngine implements TestEngine {

    JUNIT4("junit",
            Set.of(),
            "junit.framework.Test",
            "org.junit.runner.JUnitCore",
            "",
            null),
    JUNIT5("org.junit.platform.console",
            new LinkedHashSet<>(List.of(
                    "maven/org.junit.platform/junit-platform-console/1.11.4",
                    "module/org.junit.platform.console/1.11.4")),
            "org.junit.jupiter.api.Test",
            "org.junit.platform.console.ConsoleLauncher",
            "-select-class=",
            "-select-method=",
            "execute",
            "--disable-banner",
            "--disable-ansi-colors");

    private final String module;
    private final Set<String> coordinates;
    private final String markerClass;
    private final String mainClass;
    private final String prefix;
    private final String methodPrefix;
    private final List<String> arguments;

    TestDefaultEngine(String module,
                      Set<String> coordinates,
                      String markerClass,
                      String mainClass,
                      String prefix,
                      String methodPrefix,
                      String... arguments) {
        this.module = module;
        this.coordinates = coordinates;
        this.markerClass = markerClass;
        this.mainClass = mainClass;
        this.prefix = prefix;
        this.methodPrefix = methodPrefix;
        this.arguments = List.of(arguments);
    }

    @Override
    public String module() {
        return module;
    }

    @Override
    public Set<String> coordinates() {
        return coordinates;
    }

    @Override
    public String markerClass() {
        return markerClass;
    }

    @Override
    public String mainClass() {
        return mainClass;
    }

    @Override
    public String prefix() {
        return prefix;
    }

    @Override
    public String methodPrefix() {
        return methodPrefix;
    }

    @Override
    public List<String> arguments() {
        return arguments;
    }
}
