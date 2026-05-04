package build.jenesis.step;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;
import build.jenesis.maven.Pom;

import module java.base;

public class Stage implements BuildStep {

    private final BiFunction<String, String, Path> placement;

    public Stage(BiFunction<String, String, Path> placement) {
        this.placement = placement;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            if (entry.getKey().endsWith("/assign")) {
                stageAssign(context, entry.getValue());
            } else if (entry.getKey().endsWith("/pom")) {
                stagePom(context, entry, arguments);
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private void stageAssign(BuildStepContext context, BuildStepArgument argument) throws IOException {
        Path coordinates = argument.folder().resolve(COORDINATES);
        if (!Files.exists(coordinates)) {
            return;
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
            link(context, source, placement.apply(coordinate, source.getFileName().toString()));
        }
    }

    private void stagePom(BuildStepContext context,
                          Map.Entry<String, BuildStepArgument> entry,
                          SequencedMap<String, BuildStepArgument> arguments) throws IOException {
        Path pom = entry.getValue().folder().resolve(Pom.POM);
        if (!Files.exists(pom)) {
            return;
        }
        BuildStepArgument sibling = findSiblingAssign(entry.getKey(), arguments);
        if (sibling == null) {
            return;
        }
        Path coordinates = sibling.folder().resolve(COORDINATES);
        if (!Files.exists(coordinates)) {
            return;
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
            link(context, pom, placement.apply(coordinate, Pom.POM));
            return;
        }
    }

    private static BuildStepArgument findSiblingAssign(String pomKey,
                                                       SequencedMap<String, BuildStepArgument> arguments) {
        String prefix = pomKey.substring(0, pomKey.length() - "/pom".length());
        while (!prefix.isEmpty()) {
            BuildStepArgument candidate = arguments.get(prefix + "/assign");
            if (candidate != null) {
                return candidate;
            }
            int separator = prefix.lastIndexOf('/');
            if (separator == -1) {
                return null;
            }
            prefix = prefix.substring(0, separator);
        }
        return null;
    }

    private static void link(BuildStepContext context, Path source, Path relative) throws IOException {
        Path target = context.next().resolve(relative);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(target)) {
            Files.createLink(target, source);
        }
    }
}
