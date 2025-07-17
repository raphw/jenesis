package build.buildbuddy.step;

import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;

import module java.base;

public class Jar extends ProcessBuildStep {

    private final Sort sort;

    protected Jar(Function<List<String>, ? extends ProcessHandler> factory, Sort sort) {
        super(factory);
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
                                                 SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        List<String> commands = new ArrayList<>(List.of("cf", Files
                .createDirectory(context.next().resolve(ARTIFACTS))
                .resolve(sort.file)
                .toString()));
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

        CLASSES("classes.jar", BuildStep.CLASSES, BuildStep.RESOURCES),
        SOURCES("sources.jar", BuildStep.SOURCES, BuildStep.RESOURCES),
        JAVADOC("javadoc.jar", Javadoc.JAVADOC);

        final String file;
        final List<String> folders;

        Sort(String file, String... folders) {
            this.file = file;
            this.folders = List.of(folders);
        }
    }
}
