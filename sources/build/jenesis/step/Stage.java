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
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
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
                String rest = separator == -1 ? coordinate : coordinate.substring(separator + 1);
                String[] elements = rest.split("/");
                String name = switch (elements.length) {
                    case 3, 4 -> elements[1];
                    case 5 -> elements[1] + "-" + elements[3];
                    default -> rest;
                };
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
