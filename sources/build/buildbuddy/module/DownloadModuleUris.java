package build.buildbuddy.module;

import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;

import module java.base;

public class DownloadModuleUris implements BuildStep {

    public static final String URIS = "uris.properties";

    public static final URI DEFAULT = URI.create("https://raw.githubusercontent.com/" +
            "sormuras/modules/refs/heads/main/com.github.sormuras.modules/" +
            "com/github/sormuras/modules/modules.properties");

    private final String prefix;
    private final List<URI> locations;

    public DownloadModuleUris() {
        prefix = "module";
        locations = List.of(DEFAULT);
    }

    public DownloadModuleUris(String prefix, List<URI> locations) {
        this.prefix = prefix;
        this.locations = locations;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(context.next().resolve(URIS))) {
            for (URI location : locations) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        location.toURL().openStream(),
                        StandardCharsets.UTF_8))) {
                    Iterator<String> it = reader.lines().iterator();
                    while (it.hasNext()) {
                        writer.write((prefix == null ? "" : (prefix + "/")) + it.next());
                        writer.newLine();
                    }
                }
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
