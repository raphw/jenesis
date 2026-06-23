package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class Group implements BuildStep {

    public static final String GROUPS = "groups/";

    private final Function<String, Optional<String>> identification;
    private final String requiresPath;

    public <F extends Function<String, Optional<String>> & Serializable> Group(F identification) {
        this(identification, REQUIRES);
    }

    private Group(Function<String, Optional<String>> identification, String requiresPath) {
        this.identification = identification;
        this.requiresPath = requiresPath;
    }

    public Group requiresPath(String requiresPath) {
        return new Group(identification, requiresPath);
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(argument ->
                argument.hasChanged(Path.of(IDENTITY), Path.of(requiresPath)));
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        Map<String, Set<String>> from = new HashMap<>(), to = new LinkedHashMap<>();
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            String name = identification.apply(entry.getKey()).orElse(null);
            if (name == null) {
                continue;
            }
            toProperties(entry.getValue().folder().resolve(IDENTITY)).forEach(dependency -> from.computeIfAbsent(
                    dependency,
                    _ -> new LinkedHashSet<>()).add(name));
            Set<String> requires = to.computeIfAbsent(name, _ -> new LinkedHashSet<>());
            for (String coordinate : toProperties(entry.getValue().folder().resolve(requiresPath))) {
                int second = coordinate.indexOf('/', coordinate.indexOf('/') + 1);
                requires.add(coordinate.substring(second + 1));
            }
        }
        Path folder = Files.createDirectory(context.next().resolve(GROUPS));
        for (Map.Entry<String, Set<String>> entry : to.entrySet()) {
            SequencedProperties properties = new SequencedProperties();
            entry.getValue().stream()
                    .flatMap(dependency -> from.getOrDefault(dependency, Set.of()).stream())
                    .distinct()
                    .filter(name -> !name.equals(entry.getKey()))
                    .forEach(name -> properties.setProperty(name, ""));
            properties.store(folder.resolve(entry.getKey() + ".properties"));
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private static Set<String> toProperties(Path file) throws IOException {
        if (Files.exists(file)) {
            return SequencedProperties.ofFiles(file).stringPropertyNames();
        } else {
            return Set.of();
        }
    }
}
