package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;

public class Export implements BuildStep {

    private final Path target;
    private final Function<Path, Optional<Path>> placement;
    private final Consumer<Path> finalizer;

    public <F extends Function<Path, Optional<Path>> & Serializable> Export(Path target, F placement) {
        this(target, placement, _ -> {
        });
    }

    public <F extends Function<Path, Optional<Path>> & Serializable,
            C extends Consumer<Path> & Serializable> Export(Path target,
                                                            F placement,
                                                            C finalizer) {
        this.target = target;
        this.placement = placement;
        this.finalizer = finalizer;
    }

    @Override
    public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
        return true;
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
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Optional<Path> sub = placement.apply(file);
                    if (sub.isPresent()) {
                        Path destination = target.resolve(sub.get());
                        Path parent = destination.getParent();
                        if (parent != null) {
                            Files.createDirectories(parent);
                        }
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        if (Files.isDirectory(target)) {
            finalizer.accept(target);
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
