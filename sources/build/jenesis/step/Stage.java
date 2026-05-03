package build.jenesis.step;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

import module java.base;

public class Stage implements BuildStep {

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        for (var entry : arguments.entrySet()) {
            if (!entry.getKey().endsWith("/assign")) {
                continue;
            }
            Path coordinates = entry.getValue().folder().resolve(COORDINATES);
            if (!Files.exists(coordinates)) {
                continue;
            }
            Properties properties = new SequencedProperties();
            try (Reader reader = Files.newBufferedReader(coordinates)) {
                properties.load(reader);
            }
            for (String coordinate : properties.stringPropertyNames()) {
                String value = properties.getProperty(coordinate);
                if (value.isEmpty()) {
                    continue;
                }
                Path source = Path.of(value);
                if (!Files.exists(source)) {
                    continue;
                }
                int separator = coordinate.indexOf('/');
                String name = separator == -1 ? coordinate : coordinate.substring(separator + 1);
                Path folder = Files.createDirectories(context.next().resolve(name));
                Path target = folder.resolve(source.getFileName().toString());
                if (!Files.exists(target)) {
                    Files.createLink(target, source);
                }
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
