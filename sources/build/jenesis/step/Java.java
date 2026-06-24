package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.PathPlacement;

public abstract class Java extends JdkProcessBuildStep {

    private static final String MODULE_PATH = "--module-path", CLASS_PATH = "--class-path";

    protected final PathPlacement pathPlacement;
    protected final boolean jarsOnly;

    protected Java() {
        this(PathPlacement.CLASS_PATH, true);
    }

    protected Java(PathPlacement pathPlacement, boolean jarsOnly) {
        super("java", ProcessHandler.OfProcess.ofJavaHome("bin/java"));
        this.pathPlacement = pathPlacement;
        this.jarsOnly = jarsOnly;
    }

    protected Java(Function<List<String>, ? extends ProcessHandler> factory) {
        this(factory, PathPlacement.CLASS_PATH, true);
    }

    protected Java(Function<List<String>, ? extends ProcessHandler> factory,
                   PathPlacement pathPlacement,
                   boolean jarsOnly) {
        super("java", factory);
        this.pathPlacement = pathPlacement;
        this.jarsOnly = jarsOnly;
    }

    public static Java of(String... commands) {
        return of(List.of(commands));
    }

    public static Java of(PathPlacement modulePath, boolean jarsOnly, String... commands) {
        return of(modulePath, jarsOnly, List.of(commands));
    }

    public static Java of(List<String> commands) {
        return of(PathPlacement.CLASS_PATH, true, commands);
    }

    public static Java of(PathPlacement modulePath, boolean jarsOnly, List<String> commands) {
        return new Java(modulePath, jarsOnly) {
            @Override
            protected CompletionStage<List<String>> commands(Executor executor,
                                                             BuildStepContext context,
                                                             SequencedMap<String, BuildStepArgument> arguments) {
                return CompletableFuture.completedStage(commands);
            }
        };
    }

    public static Java of(Function<List<String>, ProcessHandler.OfProcess> factory, String... commands) {
        return of(factory, List.of(commands));
    }

    public static Java of(Function<List<String>, ProcessHandler.OfProcess> factory,
                          PathPlacement modulePath,
                          boolean jarsOnly,
                          String... commands) {
        return of(factory, modulePath, jarsOnly, List.of(commands));
    }

    public static Java of(Function<List<String>, ProcessHandler.OfProcess> factory, List<String> commands) {
        return of(factory, PathPlacement.CLASS_PATH, true, commands);
    }

    public static Java of(Function<List<String>, ProcessHandler.OfProcess> factory,
                          PathPlacement modulePath,
                          boolean jarsOnly,
                          List<String> commands) {
        return new Java(factory, modulePath, jarsOnly) {
            @Override
            protected CompletionStage<List<String>> commands(Executor executor,
                                                             BuildStepContext context,
                                                             SequencedMap<String, BuildStepArgument> arguments) {
                return CompletableFuture.completedStage(commands);
            }
        };
    }

    protected abstract CompletionStage<List<String>> commands(Executor executor,
                                                              BuildStepContext context,
                                                              SequencedMap<String, BuildStepArgument> arguments)
            throws IOException;

    @Override
    public CompletionStage<List<String>> process(Executor executor,
                                                 BuildStepContext context,
                                                 SequencedMap<String, BuildStepArgument> arguments,
                                                 SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        List<String> classPath = new ArrayList<>(), modulePath = new ArrayList<>();
        boolean hasAutomaticModules = false;
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            BuildStepArgument argument = entry.getValue();
            if (!jarsOnly) {
                for (String folder : List.of(Javac.CLASSES, Bind.RESOURCES)) {
                    Path candidate = argument.folder().resolve(folder);
                    if (Files.isDirectory(candidate)) {
                        (pathPlacement.test(candidate) ? modulePath : classPath).add(candidate.toString());
                    }
                }
            }
            Path artifactsFolder = argument.folder().resolve(ARTIFACTS);
            if (Files.isDirectory(artifactsFolder)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(artifactsFolder)) {
                    for (Path file : files) {
                        hasAutomaticModules |= pathPlacement.place(file, modulePath, classPath);
                    }
                }
            }
            for (Path file : Dependencies.select(argument.folder(), "runtime")) {
                hasAutomaticModules |= pathPlacement.place(file, modulePath, classPath);
            }
            SequencedMap<String, String> folders = properties.get(entry.getKey());
            if (folders != null) {
                for (Map.Entry<String, List<String>> paths : List.of(
                        Map.entry(MODULE_PATH, modulePath),
                        Map.entry(CLASS_PATH, classPath)
                )) {
                    String value = folders.remove(paths.getKey());
                    if (value != null) {
                        for (String part : value.split("\n")) {
                            if (!part.isEmpty()) {
                                paths.getValue().add(argument.folder().resolve(part).toString());
                            }
                        }
                    }
                }
            }
        }
        List<String> prefixes = new ArrayList<>();
        for (Map.Entry<String, List<String>> paths : List.of(
                Map.entry(MODULE_PATH, modulePath),
                Map.entry(CLASS_PATH, classPath)
        )) {
            if (!paths.getValue().isEmpty()) {
                prefixes.add(paths.getKey());
                prefixes.add(String.join(File.pathSeparator, paths.getValue()));
            }
        }
        if (hasAutomaticModules) {
            prefixes.add("--add-modules");
            prefixes.add("ALL-MODULE-PATH");
        }
        return commands(executor, context, arguments).thenApplyAsync(commands -> Stream.concat(
                prefixes.stream(),
                commands.stream()).toList(), executor);
    }
}
