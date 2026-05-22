package build.jenesis.step;

import module java.base;

public record TestNG() implements TestEngine {

    @Override
    public String module() {
        return "org.testng";
    }

    @Override
    public Map<String, String> markers() {
        return Map.of("Implementation-Title", "TestNG");
    }

    @Override
    public Map<String, String> runnerMarkers() {
        return Map.of("Implementation-Title", "TestNG");
    }

    @Override
    public SequencedSet<String> coordinates() {
        return Collections.emptyNavigableSet();
    }

    @Override
    public String mainClass() {
        return "org.testng.TestNG";
    }

    @Override
    public List<String> arguments() {
        return List.of();
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
