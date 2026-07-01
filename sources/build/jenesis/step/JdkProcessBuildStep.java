package build.jenesis.step;

import module java.base;

public abstract class JdkProcessBuildStep extends ProcessBuildStep {

    @SuppressWarnings("unused") // serialized to invalidate the step hash when the JVM version changes
    private final String version = Runtime.version().toString();

    protected JdkProcessBuildStep(String command, Function<List<String>, ? extends ProcessHandler> factory) {
        super(command, factory);
    }

    protected JdkProcessBuildStep(String command,
                                  Function<List<String>, ? extends ProcessHandler> factory,
                                  boolean verbose) {
        super(command, factory, verbose);
    }
}
