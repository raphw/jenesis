package build;

import module java.base;
import build.jenesis.BuildExecutorModule;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.BuildStepResult;
import build.jenesis.Project;
import build.jenesis.Repository;
import build.jenesis.Resolver;
import build.jenesis.project.DependencyScope;
import build.jenesis.project.JavaMultiProjectAssembler;
import build.jenesis.project.ModuleDescriptor;
import build.jenesis.project.MultiProjectAssembler;
import build.jenesis.project.ProjectModuleDescriptor;

public class Custom {

    static void main(String[] args) throws Exception {
        // Wrap the stock JavaMultiProjectAssembler so that every module's Java
        // sources pass through a preprocessing step before the regular compile,
        // jar, and test flow runs unchanged.
        new Project()
                .assembler(new PreprocessingAssembler(new JavaMultiProjectAssembler()))
                .resolveProperties()
                .build(args);
    }

    private record PreprocessingAssembler(MultiProjectAssembler<? super ProjectModuleDescriptor> delegate)
            implements MultiProjectAssembler<ProjectModuleDescriptor> {

        @Override
        public BuildExecutorModule apply(ProjectModuleDescriptor descriptor,
                                         Map<String, Repository> repositories,
                                         Map<String, Resolver> resolvers) {
            String original = descriptor.sources();
            ModuleDescriptor redirected = new ModuleDescriptor() {
                @Override
                public String name() {
                    return descriptor.name();
                }

                @Override
                public SequencedSet<String> dependencies() {
                    return descriptor.dependencies();
                }

                @Override
                public String sources() {
                    return "preprocess";
                }

                @Override
                public SequencedSet<String> resources() {
                    return descriptor.resources();
                }

                @Override
                public String manifests() {
                    return descriptor.manifests();
                }

                @Override
                public String coordinates() {
                    return descriptor.coordinates();
                }

                @Override
                public String artifacts(DependencyScope scope) {
                    return descriptor.artifacts(scope);
                }

                @Override
                public String resolved(DependencyScope scope) {
                    return descriptor.resolved(scope);
                }
            };
            ProjectModuleDescriptor wrapped = new ProjectModuleDescriptor(redirected,
                    descriptor.test(),
                    descriptor.source(),
                    descriptor.documentation(),
                    descriptor.strictPinning());
            BuildExecutorModule inner = delegate.apply(wrapped, repositories, resolvers);
            return (sub, inherited) -> {
                sub.addStep("preprocess", new Preprocess(), original);
                inner.accept(sub, inherited);
            };
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
