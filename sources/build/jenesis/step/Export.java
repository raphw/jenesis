package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class Export implements BuildStep {

    private final Path target;
    private final FilePlacement placement;
    private final Consumer<Path> finalizer;

    public Export(Path target, FilePlacement placement) {
        this(target, placement, _ -> {
        });
    }

    public <C extends Consumer<Path> & Serializable> Export(Path target,
                                                            FilePlacement placement,
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
                    SequencedProperties module = new SequencedProperties();
                    SequencedProperties metadata = new SequencedProperties();
                    Path parent = file.getParent();
                    if (parent != null) {
                        Path moduleFile = parent.resolve(BuildStep.MODULE);
                        if (Files.isRegularFile(moduleFile)) {
                            module.putAll(SequencedProperties.ofFiles(moduleFile));
                        }
                        Path metadataFile = parent.resolve(BuildStep.METADATA);
                        if (Files.isRegularFile(metadataFile)) {
                            metadata.putAll(SequencedProperties.ofFiles(metadataFile));
                        }
                    }
                    Optional<Path> sub = placement.apply(file, module, metadata);
                    if (sub.isPresent()) {
                        Path destination = target.resolve(sub.get());
                        Path destinationParent = destination.getParent();
                        if (destinationParent != null) {
                            Files.createDirectories(destinationParent);
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
