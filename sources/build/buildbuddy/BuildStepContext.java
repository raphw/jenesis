package build.buildbuddy;

import java.nio.file.Path;

public record BuildStepContext(Path previous, Path next, Path supplement) {
}
