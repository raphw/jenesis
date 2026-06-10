package build.jenesis.project;

import module java.base;
import build.jenesis.BuildStep;

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
    public List<String> commands(Path supplement,
                                 Path output,
                                 SequencedSet<String> classes,
                                 SequencedMap<String, SequencedSet<String>> methods,
                                 SequencedSet<String> groups,
                                 boolean parallel,
                                 boolean reporting) {
        List<String> commands = new ArrayList<>(List.of("execute", "--disable-banner", "--disable-ansi-colors"));
        for (String group : groups) {
            commands.add("--include-tag=" + group);
        }
        if (parallel) {
            commands.add("--config=junit.jupiter.execution.parallel.enabled=true");
            commands.add("--config=junit.jupiter.execution.parallel.mode.default=concurrent");
        }
        if (reporting) {
            commands.add("--config=junit.platform.reporting.open.xml.enabled=true");
            commands.add("--config=junit.platform.reporting.output.dir=" + output.resolve(BuildStep.REPORTS + "tests"));
        }
        for (String className : classes) {
            commands.add("--select-class=" + className);
        }
        for (Map.Entry<String, SequencedSet<String>> entry : methods.entrySet()) {
            for (String method : entry.getValue()) {
                commands.add("--select-method=" + entry.getKey() + "#" + method);
            }
        }
        return commands;
    }
}
