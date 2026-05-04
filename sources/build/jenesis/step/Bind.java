package build.jenesis.step;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;

import module java.base;

public class Bind implements BuildStep {

    private final Map<Path, Path> paths;
    private final Function<Path, Optional<Path>> placement;
    private final Set<Path> prefixes;

    public Bind(Map<Path, Path> paths) {
        this.paths = paths;
        this.placement = null;
        this.prefixes = null;
    }

    public Bind(Function<Path, Optional<Path>> placement) {
        this(placement, null);
    }

    public Bind(Function<Path, Optional<Path>> placement, Set<Path> prefixes) {
        this.paths = null;
        this.placement = placement;
        this.prefixes = prefixes;
    }

    public static Bind asSources() {
        return new Bind(Map.of(Path.of("."), Path.of(SOURCES)));
    }

    public static Bind asResources() {
        return new Bind(Map.of(Path.of("."), Path.of(RESOURCES)));
    }

    public static Bind asCoordinates(String name) {
        return new Bind(Map.of(Path.of(name == null ? COORDINATES : name), Path.of(COORDINATES)));
    }

    public static Bind asDependencies(String name) {
        return new Bind(Map.of(Path.of(name), Path.of(DEPENDENCIES)));
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        if (paths != null) {
            return arguments.values().stream().anyMatch(argument -> argument.hasChanged(paths.keySet()));
        }
        if (prefixes != null) {
            return arguments.values().stream().anyMatch(argument -> argument.hasChanged(prefixes));
        }
        return arguments.values().stream().anyMatch(BuildStepArgument::hasChanged);
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        if (paths != null) {
            applyPaths(context, arguments);
        } else {
            applyPlacement(context, arguments);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }

    private void applyPaths(BuildStepContext context, SequencedMap<String, BuildStepArgument> arguments) throws IOException {
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
                            Files.createLink(target.resolve(source.relativize(file)), file);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
        }
    }

    private void applyPlacement(BuildStepContext context, SequencedMap<String, BuildStepArgument> arguments) throws IOException {
        for (BuildStepArgument argument : arguments.values()) {
            Path folder = argument.folder();
            if (!Files.isDirectory(folder)) {
                continue;
            }
            List<Path> roots;
            if (prefixes == null) {
                roots = List.of(folder);
            } else {
                roots = new ArrayList<>();
                for (Path prefix : prefixes) {
                    Path candidate = folder.resolve(prefix);
                    if (Files.exists(candidate)) {
                        roots.add(candidate);
                    }
                }
            }
            for (Path root : roots) {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Optional<Path> target = placement.apply(file);
                        if (target.isPresent()) {
                            Path resolved = context.next().resolve(target.get());
                            Path parent = resolved.getParent();
                            if (parent != null) {
                                Files.createDirectories(parent);
                            }
                            if (!Files.exists(resolved)) {
                                Files.createLink(resolved, file);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
    }
}
