package build.jenesis.project;

import module java.base;

public record JUnitPlatform() implements TestEngine {

    @Override
    public String runnerModule() {
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
    public SequencedMap<String, String> coordinates(ModuleDescriptor engine) {
        String version = engine == null ? null : engine.rawVersion().orElse(null);
        SequencedMap<String, String> coordinates = new LinkedHashMap<>();
        coordinates.put("module/org.junit.platform.console", version);
        coordinates.put("maven/org.junit.platform/junit-platform-console", version == null ? "RELEASE" : version);
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
    public List<String> arguments(Path supplement, String group, boolean parallel) {
        List<String> arguments = new ArrayList<>(List.of("execute", "--disable-banner", "--disable-ansi-colors"));
        if (group != null) {
            arguments.add("--include-tag=" + group);
        }
        if (parallel) {
            arguments.add("--config=junit.jupiter.execution.parallel.enabled=true");
            arguments.add("--config=junit.jupiter.execution.parallel.mode.default=concurrent");
        }
        return arguments;
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
