package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class Assign implements BuildStep {

    private final BiFunction<Set<String>, SequencedSet<Path>, Map<String, Path>> assigner;

    public Assign() {
        this((BiFunction<Set<String>, SequencedSet<Path>, Map<String, Path>> & Serializable) ((coordinates, files) -> {
            if (files.size() != 1) {
                throw new IllegalArgumentException("Expected exactly one artifact: " + files);
            }
            return coordinates.stream().collect(Collectors.toMap(Function.identity(), _ -> files.getFirst()));
        }));
    }

    private Assign(BiFunction<Set<String>, SequencedSet<Path>, Map<String, Path>> assigner) {
        this.assigner = assigner;
    }

    public <F extends BiFunction<Set<String>, SequencedSet<Path>, Map<String, Path>> & Serializable> Assign assigner(F assigner) {
        return new Assign(assigner);
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(argument ->
                argument.hasChanged(Path.of(ARTIFACTS), Path.of(IDENTITY), Path.of(JMod.JMODS)));
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        SequencedProperties assignments = new SequencedProperties();
        SequencedSet<Path> files = new TreeSet<>();
        SequencedSet<Path> jmods = new TreeSet<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path artifacts = argument.folder().resolve(ARTIFACTS);
            if (Files.exists(artifacts)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifacts)) {
                    for (Path artifact : stream) {
                        files.add(artifact);
                    }
                }
            }
            Path archives = argument.folder().resolve(JMod.JMODS);
            if (Files.exists(archives)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(archives)) {
                    for (Path archive : stream) {
                        jmods.add(archive);
                    }
                }
            }
            Path coordinates = argument.folder().resolve(IDENTITY);
            if (Files.exists(coordinates)) {
                SequencedProperties properties = SequencedProperties.ofFiles(coordinates);
                for (String name : properties.stringPropertyNames()) {
                    String value = properties.getProperty(name);
                    if (value.isEmpty()) {
                        assignments.put(name, "");
                    } else {
                        Path resolved = argument.folder().resolve(value);
                        assignments.put(name, context.next()
                                .relativize(resolved)
                                .toString()
                                .replace(File.separatorChar, '/'));
                    }
                }
            }
        }
        Map<String, Path> assigned = assigner.apply(assignments.stringPropertyNames().stream()
                        .filter(assignment -> assignments.getProperty(assignment).isEmpty())
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                files);
        assigned.forEach((coordinate, path) -> {
            if (!files.contains(path)) {
                throw new IllegalArgumentException("Unknown path " + path);
            }
            assignments.setProperty(coordinate, context.next()
                    .relativize(path)
                    .toString()
                    .replace(File.separatorChar, '/'));
        });
        if (!jmods.isEmpty()) {
            // Publish the produced module's link-time form alongside the jar, so a consumer
            // can resolve <coordinate>:jmod the way it resolves the jar (falling back to the
            // jar in the repository when no jmod was produced).
            String archive = context.next()
                    .relativize(jmods.getFirst())
                    .toString()
                    .replace(File.separatorChar, '/');
            for (String coordinate : assigned.keySet()) {
                assignments.setProperty(coordinate + ":jmod", archive);
            }
        }
        assignments.store(context.next().resolve(IDENTITY));
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
