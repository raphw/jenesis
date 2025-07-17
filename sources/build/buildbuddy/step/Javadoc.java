package build.buildbuddy.step;

import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;

import module java.base;

public class Javadoc extends ProcessBuildStep {

    public static final String JAVADOC = "javadoc/";

    protected Javadoc(Function<List<String>, ? extends ProcessHandler> factory) {
        super(factory);
    }

    public static Javadoc tool() {
        return new Javadoc(ProcessHandler.OfTool.of("javadoc"));
    }

    public static Javadoc process() {
        return new Javadoc(ProcessHandler.OfProcess.ofJavaHome("bin/javadoc"));
    }

    @Override
    protected CompletionStage<List<String>> process(Executor executor,
                                                    BuildStepContext context,
                                                    SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        List<String> commands = new ArrayList<>(List.of("-d", Files
                .createDirectory(context.next().resolve(JAVADOC))
                .toString()));
        for (BuildStepArgument argument : arguments.values()) {
            Path folder = argument.folder().resolve(BuildStep.SOURCES);
            if (Files.exists(folder)) {
                Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.toString().endsWith(".java")) {
                            commands.add(file.toString());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        return CompletableFuture.completedStage(commands);
    }
}
