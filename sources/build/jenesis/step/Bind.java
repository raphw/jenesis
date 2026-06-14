package build.jenesis.step;

import module java.base;
import build.jenesis.BuildExecutor;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;

public class Bind implements BuildStep {

    private final Map<Path, Path> paths;

    public Bind(Map<Path, Path> paths) {
        this.paths = paths;
    }

    public static Bind asSources() {
        return new Bind(Map.of(Path.of("."), Path.of(SOURCES)));
    }

    public static Bind asResources() {
        return new Bind(Map.of(Path.of("."), Path.of(RESOURCES)));
    }

    public static Bind asIdentity(String name) {
        return new Bind(Map.of(Path.of(name == null ? IDENTITY : name), Path.of(IDENTITY)));
    }

    public static Bind asRequires(String name) {
        return new Bind(Map.of(Path.of(name), Path.of(REQUIRES)));
    }

    public static Bind asMetadata() {
        return new Bind(Map.of(Path.of(""), Path.of(METADATA)));
    }

    public static void configured(BuildExecutor buildExecutor,
                                  SequencedSet<String> inputs,
                                  String name,
                                  boolean enabled,
                                  Path configurationFile,
                                  BuildExecutorModule module) {
        if (!enabled || configurationFile == null) {
            return;
        }
        buildExecutor.addModule(name, (nested, inherited) -> {
            nested.addSource("configuration",
                    new Bind(Map.of(Path.of(""), configurationFile.getFileName())),
                    configurationFile);
            SequencedSet<String> toolInputs = new LinkedHashSet<>();
            toolInputs.add("configuration");
            toolInputs.addAll(inherited.sequencedKeySet());
            nested.addModule("tool", module, toolInputs);
        }, inputs);
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return arguments.values().stream().anyMatch(argument -> argument.hasChanged(paths.keySet()));
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        for (BuildStepArgument argument : arguments.values()) {
            for (Map.Entry<Path, Path> entry : paths.entrySet()) {
                Path source = argument.folder().resolve(entry.getKey());
                if (Files.exists(source)) {
                    Path target = context.next().resolve(entry.getValue());
                    if (!Objects.equals(target.getParent(), context.next())) {
                        Files.createDirectories(target.getParent());
                    }
                    Files.walkFileTree(source, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                            Files.createDirectories(target.resolve(source.relativize(dir)));
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            BuildStep.linkOrCopy(target.resolve(source.relativize(file)), file);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
