package build.jenesis.step;

import module java.base;

public record JUnit4() implements TestEngine {

    @Override
    public String module() {
        return "junit";
    }

    @Override
    public Map<String, String> markers() {
        return Map.of("Implementation-Title", "JUnit");
    }

    @Override
    public Map<String, String> runnerMarkers() {
        return Map.of("Implementation-Title", "JUnit");
    }

    @Override
    public Set<String> coordinates() {
        return Set.of();
    }

    @Override
    public String mainClass() {
        return "org.junit.runner.JUnitCore";
    }

    @Override
    public List<String> arguments() {
        return List.of();
    }

    @Override
    public List<String> commands(List<String> classes, SequencedMap<String, List<String>> methods) {
        if (!methods.isEmpty()) {
            throw new IllegalArgumentException("JUnit4 does not support running individual methods");
        }
        return new ArrayList<>(classes);
    }
}
