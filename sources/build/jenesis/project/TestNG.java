package build.jenesis.project;

import module java.base;

public record TestNG() implements TestEngine {

    @Override
    public String runnerModule() {
        return "org.testng";
    }

    @Override
    public boolean isEngine(ModuleDescriptor module) {
        return module.name().equals("org.testng");
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
        return "org.testng.TestNG";
    }

    @Override
    public List<String> arguments(Path supplement) {
        return List.of("-d", supplement.resolve("test-output").toString());
    }

    @Override
    public List<String> commands(List<String> classes, SequencedMap<String, List<String>> methods) {
        List<String> commands = new ArrayList<>();
        if (!classes.isEmpty()) {
            commands.add("-testclass");
            commands.add(String.join(",", classes));
        }
        if (!methods.isEmpty()) {
            List<String> joined = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : methods.entrySet()) {
                for (String method : entry.getValue()) {
                    joined.add(entry.getKey() + "." + method);
                }
            }
            commands.add("-methods");
            commands.add(String.join(",", joined));
        }
        return commands;
    }
}
