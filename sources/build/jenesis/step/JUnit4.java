package build.jenesis.step;

import module java.base;

public record JUnit4() implements TestEngine {

    public static final String MARKER_CLASS = "junit.framework.Test";

    @Override
    public String module() {
        return "junit";
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
    public String prefix() {
        return "";
    }

    @Override
    public List<String> arguments() {
        return List.of();
    }
}
