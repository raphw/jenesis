package build.buildbuddy;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Javac extends AbstractProcessBuildStep {

    private final String javac;

    public Javac() {
        String home = System.getProperty("java.home");
        if (home == null) {
            javac = null;
        } else {
            File javac = new File(home, "bin/javac");
            this.javac = javac.isFile() ? javac.getPath() : null;
        }
    }

    @Override
    protected CompletionStage<ProcessBuilder> process(Executor executor,
                                                      Path previous,
                                                      Path target,
                                                      Map<String, BuildResult> dependencies) {
        List<String> commands = new ArrayList<>(Arrays.asList(
                javac,
                "--release", Integer.toString(Runtime.version().version().getFirst()),
                "-d", target.toString()
        ));
        dependencies.values().stream().flatMap(result -> result.files().keySet().stream()
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .map(path -> result.folder().resolve(path).toString())).forEach(commands::add);
        return CompletableFuture.completedStage(new ProcessBuilder(commands));
    }
}
