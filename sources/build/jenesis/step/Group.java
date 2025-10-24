package build.jenesis.step;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

import module java.base;

public class Group implements BuildStep {

    public static final String GROUPS = "groups/";

    private final Function<String, Optional<String>> identification;

    public Group(Function<String, Optional<String>> identification) {
        this.identification = identification;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        // TODO: improve incremental resolve
        Map<String, Set<String>> from = new HashMap<>(), to = new LinkedHashMap<>();
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            String name = identification.apply(entry.getKey()).orElse(null);
            if (name == null) {
                continue;
            }
            toProperties(entry.getValue().folder().resolve(COORDINATES)).forEach(dependency -> from.computeIfAbsent(
                    dependency,
                    _ -> new LinkedHashSet<>()).add(name));
            to.computeIfAbsent(name, _ -> new LinkedHashSet<>()).addAll(toProperties(entry.getValue()
                    .folder()
                    .resolve(DEPENDENCIES)));
        }
        Path folder = Files.createDirectory(context.next().resolve(GROUPS));
        for (Map.Entry<String, Set<String>> entry : to.entrySet()) {
            Properties properties = new SequencedProperties();
            entry.getValue().stream()
                    .flatMap(dependency -> from.getOrDefault(dependency, Set.of()).stream())
                    .distinct()
                    .forEach(name -> properties.setProperty(name, ""));
            try (Writer writer = Files.newBufferedWriter(folder.resolve(URLEncoder.encode(
                    entry.getKey(),
                    StandardCharsets.UTF_8) + ".properties"))) {
                properties.store(writer, null);
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static Set<String> toProperties(Path file) throws IOException {
        if (Files.exists(file)) {
            Properties properties = new SequencedProperties();
            try (Reader reader = Files.newBufferedReader(file)) {
                properties.load(reader);
            }
            return properties.stringPropertyNames();
        } else {
            return Set.of();
        }
    }
}
