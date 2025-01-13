package build.buildbuddy;

import java.util.concurrent.CompletionException;

public class BuildExecutionException extends CompletionException {

    private final String step;

    BuildExecutionException(String prefix, BuildExecutionException exception) {
        this(prefix + "/" + exception.step, exception.getCause());
    }

    BuildExecutionException(String step, Throwable throwable) {
        super("Failed to execute " + step, throwable);
        this.step = step;
    }
}
