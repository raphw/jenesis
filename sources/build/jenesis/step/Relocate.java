package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.SequencedProperties;

public class Relocate implements BuildStep {

    private final FilePlacement placement;
    private final Set<Path> prefixes;

    public Relocate(FilePlacement placement) {
        this(placement, null);
    }

    public Relocate(FilePlacement placement, Set<Path> prefixes) {
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
                        Properties metadata = new SequencedProperties();
                        Path parent = file.getParent();
                        if (parent != null) {
                            Path moduleFile = parent.resolve(BuildStep.MODULE);
                            if (Files.isRegularFile(moduleFile)) {
                                metadata.putAll(SequencedProperties.ofFiles(moduleFile));
                            }
                            Path metadataFile = parent.resolve(BuildStep.METADATA);
                            if (Files.isRegularFile(metadataFile)) {
                                metadata.putAll(SequencedProperties.ofFiles(metadataFile));
                            }
                        }
                        Optional<Path> target = placement.apply(file, metadata);
                        if (target.isPresent()) {
                            Path resolved = context.next().resolve(target.get());
                            Path resolvedParent = resolved.getParent();
                            if (resolvedParent != null) {
                                Files.createDirectories(resolvedParent);
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
