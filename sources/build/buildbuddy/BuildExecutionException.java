package build.buildbuddy;

public class BuildExecutionException extends RuntimeException {

    private final String step;

    BuildExecutionException(String prefix, BuildExecutionException exception) {
        this(prefix + "/" + exception.step, exception.getCause());
    }

    BuildExecutionException(String step, Throwable throwable) {
        super("Failed to execute " + step, throwable, false, false);
        this.step = step;
    }
}
