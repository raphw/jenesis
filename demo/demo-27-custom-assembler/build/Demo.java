package build;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Project;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.project.AssemblyDescriptor;
import build.jenesis.project.InferredMultiProjectAssembler;
import build.jenesis.project.MultiProjectAssembler;
import build.jenesis.project.ProjectModuleDescriptor;

public class Demo {

    static void main(String[] args) throws Exception {
        // Wrap the stock InferredMultiProjectAssembler so that every module's Java
        // sources pass through a preprocessing step before the regular compile,
        // jar, and test flow runs unchanged.
        new Project()
                .assembler(new PreprocessingAssembler(new InferredMultiProjectAssembler()))
                .build(args);
    }

    private record PreprocessingAssembler(MultiProjectAssembler<? super ProjectModuleDescriptor> delegate)
            implements MultiProjectAssembler<ProjectModuleDescriptor> {

        @Override
        public AssemblyDescriptor apply(ProjectModuleDescriptor descriptor,
                                        Map<String, Repository> repositories,
                                        Map<String, Resolver> resolvers) {
            // Redirect the descriptor's sources to a preprocessing step using
            // the ProjectModuleDescriptor wither, then let the stock assembler
            // wire the regular flow against the preprocessed tree. Only the build
            // phase is decorated; any later (packaging) phases pass through.
            SequencedSet<String> original = descriptor.sources();
            ProjectModuleDescriptor redirected = descriptor.sources("preprocess");
            return delegate.apply(redirected, repositories, resolvers).mapBuild(inner -> (sub, inherited) -> {
                sub.addStep("preprocess", new Preprocess(), original.stream());
                inner.accept(sub, inherited);
            });
        }
    }

    private record Preprocess() implements BuildStep {

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            Path target = context.next().resolve(BuildStep.SOURCES);
            for (BuildStepArgument argument : arguments.values()) {
                Path sources = argument.folder().resolve(BuildStep.SOURCES);
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                Files.walkFileTree(sources, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                            throws IOException {
                        Files.createDirectories(target.resolve(sources.relativize(directory)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                        Path destination = target.resolve(sources.relativize(file));
                        if (file.toString().endsWith(".java")) {
                            String content = Files.readString(file);
                            String substituted = content.replace("${greeting}",
                                    "Hello from a source preprocessed by a custom assembler!");
                            if (!substituted.equals(content)) {
                                System.out.println("custom-assembler: substituted ${greeting} in "
                                        + sources.relativize(file));
                            }
                            Files.writeString(destination, substituted);
                        } else {
                            BuildStep.linkOrCopy(destination, file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}
