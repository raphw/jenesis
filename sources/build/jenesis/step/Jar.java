package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;

public class Jar extends JdkProcessBuildStep {

    private final Sort sort;

    protected Jar(Function<List<String>, ? extends ProcessHandler> factory, Sort sort) {
        super("jar", factory);
        this.sort = sort;
    }

    public static Jar tool(Sort sort) {
        return new Jar(ProcessHandler.OfTool.of("jar"), sort);
    }

    public static Jar process(Sort sort) {
        return new Jar(ProcessHandler.OfProcess.ofJavaHome("bin/jar"), sort);
    }

    @Override
    public CompletionStage<List<String>> process(Executor executor,
                                                 BuildStepContext context,
                                                 SequencedMap<String, BuildStepArgument> arguments,
                                                 SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        List<String> commands = new ArrayList<>(List.of(
                "--create",
                "--file",
                Files.createDirectory(context.next().resolve(sort.folder))
                        .resolve(sort.file)
                        .toString(),
                "--date=1980-01-01T00:00:02Z"));
        for (BuildStepArgument argument : arguments.values()) {
            for (String name : sort.folders) {
                Path folder = argument.folder().resolve(name);
                if (Files.exists(folder)) {
                    commands.add("-C");
                    commands.add(folder.toString());
                    commands.add(".");
                }
            }
        }
        return CompletableFuture.completedStage(commands);
    }

    public enum Sort {

        CLASSES("classes.jar", BuildStep.ARTIFACTS, BuildStep.CLASSES, BuildStep.RESOURCES),
        SOURCES("sources.jar", BuildStep.SOURCES, BuildStep.SOURCES, BuildStep.RESOURCES),
        JAVADOC("javadoc.jar", BuildStep.DOCUMENTATION, Javadoc.JAVADOC);

        final String file;
        final String folder;
        final List<String> folders;

        Sort(String file, String folder, String... folders) {
            this.file = file;
            this.folder = folder;
            this.folders = List.of(folders);
        }
    }
}
