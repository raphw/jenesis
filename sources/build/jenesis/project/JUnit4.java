package build.jenesis.project;

import module java.base;

public record JUnit4() implements TestEngine {

    @Override
    public String runnerModule() {
        return "junit";
    }

    @Override
    public boolean isEngine(ModuleDescriptor module) {
        return module.name().equals("junit");
    }

    @Override
    public boolean isRunner(ModuleDescriptor module) {
        return isEngine(module);
    }

    @Override
    public SequencedMap<String, String> coordinates(ModuleDescriptor engine) {
        return Collections.emptyNavigableMap();
    }

    @Override
    public String mainClass() {
        return "org.junit.runner.JUnitCore";
    }

    @Override
    public List<String> arguments(Path supplement, String group, boolean parallel) {
        if (group != null || parallel) {
            throw new IllegalArgumentException("JUnit4 does not support test groups or parallel execution");
        }
        return List.of();
    }

    @Override
    public List<String> commands(List<String> classes, SequencedMap<String, List<String>> methods) {
        if (!methods.isEmpty()) {
            throw new IllegalArgumentException("JUnit4 does not support running individual methods");
        }
        return classes;
    }
}
