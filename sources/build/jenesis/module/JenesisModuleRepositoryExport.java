package build.jenesis.module;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;

public class JenesisModuleRepositoryExport implements BuildStep {

    private final Path target;

    public JenesisModuleRepositoryExport() {
        this(Path.of(System.getProperty("user.home")).resolve(".jenesis"));
    }

    public JenesisModuleRepositoryExport(Path target) {
        this.target = target;
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
        Set<Path> cleaned = new HashSet<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path folder = argument.folder();
            if (!Files.isDirectory(folder)) {
                continue;
            }
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Path relative = folder.relativize(file);
                    Path destination = target.resolve(relative.toString());
                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                        if (cleaned.add(parent)) {
                            try (DirectoryStream<Path> existing = Files.newDirectoryStream(parent)) {
                                for (Path child : existing) {
                                    if (Files.isRegularFile(child)) {
                                        Files.delete(child);
                                    }
                                }
                            }
                        }
                    }
                    Files.deleteIfExists(destination);
                    try {
                        Files.createLink(destination, file);
                    } catch (UnsupportedOperationException | FileSystemException _) {
                        Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
