package build.jenesis.step;

import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;

import module java.base;

public class Relocate implements BuildStep {

    private final Function<Path, Optional<Path>> placement;
    private final Set<Path> prefixes;

    public Relocate(Function<Path, Optional<Path>> placement) {
        this(placement, null);
    }

    public Relocate(Function<Path, Optional<Path>> placement, Set<Path> prefixes) {
        this.placement = placement;
        this.prefixes = prefixes;
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
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
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
