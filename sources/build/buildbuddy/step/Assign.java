package build.buildbuddy.step;

import build.buildbuddy.*;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Assign implements BuildStep {

    private final BiFunction<Set<String>, SequencedSet<Path>, Map<String, Path>> assigner;

    public Assign() {
        assigner = (coordinates, files) -> {
            if (files.size() != 1) {
                throw new IllegalArgumentException("Expected exactly one artifact: " + files);
            }
            return coordinates.stream().collect(Collectors.toMap(Function.identity(), _ -> files.getFirst()));
        };
    }

    public Assign(BiFunction<Set<String>, SequencedSet<Path>, Map<String, Path>> assigner) {
        this.assigner = assigner;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        // TODO: improve incremental resolve
        Properties assignments = new SequencedProperties();
        SequencedSet<Path> files = new LinkedHashSet<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path artifacts = argument.folder().resolve(ARTIFACTS);
            if (Files.exists(artifacts)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifacts)) {
                    for (Path artifact : stream) {
                        files.add(artifact);
                    }
                }
            }
            Path coordinates = argument.folder().resolve(COORDINATES);
            if (Files.exists(coordinates)) {
                Properties properties = new SequencedProperties();
                try (Reader reader = Files.newBufferedReader(coordinates)) {
                    properties.load(reader);
                }
                properties.stringPropertyNames().forEach(name -> assignments.put(name, properties.getProperty(name)));
            }
        }
        assigner.apply(assignments.stringPropertyNames().stream()
                .filter(assignment -> assignments.getProperty(assignment).isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new)), files).forEach((coordinate, path) -> {
            if (!files.contains(path)) {
                throw new IllegalArgumentException("Unknown path " + path);
            }
            assignments.setProperty(coordinate, path.toString());
        });
        try (Writer writer = Files.newBufferedWriter(context.next().resolve(COORDINATES))) {
            assignments.store(writer, null);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
