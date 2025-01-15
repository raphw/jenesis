package build.buildbuddy;

import java.util.concurrent.CompletionException;

public class BuildExecutorException extends CompletionException {

    public BuildExecutorException(String identity, Throwable cause) {
        super("Failed to execute " + identity, cause);
    }
}
