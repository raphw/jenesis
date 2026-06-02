package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;

public class JPackage extends JdkProcessBuildStep {

    public static final String PACKAGES = "packages/";

    protected JPackage(Function<List<String>, ? extends ProcessHandler> factory) {
        super("jpackage", factory);
    }

    public static JPackage tool() {
        return new JPackage(ProcessHandler.OfTool.of("jpackage"));
    }

    public static JPackage process() {
        return new JPackage(ProcessHandler.OfProcess.ofJavaHome("bin/jpackage"));
    }

    @Override
    protected CompletionStage<List<String>> process(Executor executor,
                                                    BuildStepContext context,
                                                    SequencedMap<String, BuildStepArgument> arguments,
                                                    SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        Path input = Files.createDirectory(context.supplement().resolve("input"));
        List<String> staged = new ArrayList<>();
        for (BuildStepArgument argument : arguments.values()) {
            for (String jarFolder : List.of(BuildStep.ARTIFACTS, BuildStep.DEPENDENCIES)) {
                Path jars = argument.folder().resolve(jarFolder);
                if (Files.exists(jars)) {
                    Files.walkFileTree(jars, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (file.toString().endsWith(".jar")) {
                                BuildStep.linkOrCopy(input.resolve(file.getFileName()), file);
                                staged.add(file.getFileName().toString());
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
        }
        if (staged.isEmpty()) {
            return CompletableFuture.completedStage(null);
        }
        List<String> commands = new ArrayList<>(List.of(
                "--input", input.toString(),
                "--dest", Files.createDirectory(context.next().resolve(PACKAGES)).toString()));
        return CompletableFuture.completedStage(commands);
    }
}
