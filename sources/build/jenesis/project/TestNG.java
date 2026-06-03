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
    public List<String> commands(Path supplement,
                                 Path output,
                                 SequencedSet<String> classes,
                                 SequencedMap<String, SequencedSet<String>> methods,
                                 SequencedSet<String> groups,
                                 boolean parallel,
                                 boolean reporting) {
        List<String> commands = new ArrayList<>(List.of("-d", supplement.resolve("test-output").toString()));
        if (!groups.isEmpty()) {
            commands.add("-groups");
            commands.add(String.join(",", groups));
        }
        if (parallel) {
            commands.add("-parallel");
            commands.add("methods");
        }
        if (!classes.isEmpty()) {
            commands.add("-testclass");
            commands.add(String.join(",", classes));
        }
        if (!methods.isEmpty()) {
            List<String> joined = new ArrayList<>();
            for (Map.Entry<String, SequencedSet<String>> entry : methods.entrySet()) {
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
