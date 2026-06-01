package build.jenesis.project;

import module java.base;

public record JUnitPlatform() implements TestEngine {

    @Override
    public String module() {
        return "org.junit.platform.console";
    }

    @Override
    public boolean isEngine(ModuleDescriptor module) {
        return module.name().equals("org.junit.platform.engine");
    }

    @Override
    public boolean isRunner(ModuleDescriptor module) {
        return module.name().equals("org.junit.platform.console");
    }

    @Override
    public SequencedSet<String> coordinates(ModuleDescriptor engine) {
        String version = engine == null ? null : engine.rawVersion().orElse(null);
        SequencedSet<String> coordinates = new LinkedHashSet<>();
        coordinates.add("module/org.junit.platform.console" + (version == null ? "" : "/" + version));
        coordinates.add("maven/org.junit.platform/junit-platform-console/" + (version == null ? "RELEASE" : version));
        return coordinates;
    }

    @Override
    public String mainClass() {
        return "org.junit.platform.console.ConsoleLauncher";
    }

    @Override
    public Map<String, String> properties() {
        return Map.of("org.jline.terminal.dumb", "true");
    }

    @Override
    public List<String> arguments(Path supplement) {
        return List.of("execute", "--disable-banner", "--disable-ansi-colors");
    }

    @Override
    public List<String> commands(List<String> classes, SequencedMap<String, List<String>> methods) {
        List<String> commands = new ArrayList<>();
        for (String className : classes) {
            commands.add("--select-class=" + className);
        }
        for (Map.Entry<String, List<String>> entry : methods.entrySet()) {
            for (String method : entry.getValue()) {
                commands.add("--select-method=" + entry.getKey() + "#" + method);
            }
        }
        return commands;
    }
}
