package build.buildbuddy.step;

import build.buildbuddy.*;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Group implements BuildStep {

    public static final String GROUPS = "groups/";

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        // TODO: improve incremental resolve
        Map<String, Set<String>> from = new LinkedHashMap<>(), link = new LinkedHashMap<>(), to = new LinkedHashMap<>();
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            Path coordinates = entry.getValue().folder().resolve(COORDINATES);
            if (!Files.exists(coordinates)) {
                continue;
            }
            Set<String> sources;
            try (Reader reader = Files.newBufferedReader(coordinates)) {
                Properties properties = new SequencedProperties();
                properties.load(reader);
                sources = properties.stringPropertyNames();
            }
            Path dependencies = entry.getValue().folder().resolve(DEPENDENCIES);
            Set<String> targets;
            if (Files.exists(coordinates)) {
                Properties properties = new SequencedProperties();
                try (Reader reader = Files.newBufferedReader(dependencies)) {
                    properties.load(reader);
                }
                targets = properties.stringPropertyNames();
            } else {
                targets = Set.of();
            }
            from.computeIfAbsent(entry.getKey(), _ -> new LinkedHashSet<>()).addAll(sources);
            sources.forEach(source -> link.computeIfAbsent(source, _ -> new LinkedHashSet<>()).addAll(targets));
            sources.forEach(source -> to.computeIfAbsent(source, _ -> new LinkedHashSet<>()).add(entry.getKey()));
        }
        Path folder = Files.createDirectory(context.next().resolve(GROUPS));
        for (String name : arguments.keySet()) {
            Properties properties = new SequencedProperties();
            from.get(name).stream()
                    .flatMap(value -> link.getOrDefault(value, Set.of()).stream())
                    .flatMap(value -> to.getOrDefault(value, Set.of()).stream())
                    .forEach(value -> properties.setProperty(value, ""));
            try (Writer writer = Files.newBufferedWriter(folder.resolve(name.replace('/', '.') + ".properties"))) {
                properties.store(writer, null);
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
