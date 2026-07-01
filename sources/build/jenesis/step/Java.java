package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.PathPlacement;

public abstract class Java extends JdkProcessBuildStep {

    private static final String MODULE_PATH = "--module-path", CLASS_PATH = "--class-path";

    protected final PathPlacement pathPlacement;
    protected final boolean jarsOnly;
    protected final String group;

    protected Java(Function<List<String>, ? extends ProcessHandler> factory,
                   PathPlacement pathPlacement,
                   boolean jarsOnly,
                   String group) {
        this(factory, pathPlacement, jarsOnly, group, printing("java"));
    }

    protected Java(Function<List<String>, ? extends ProcessHandler> factory,
                   PathPlacement pathPlacement,
                   boolean jarsOnly,
                   String group,
                   boolean verbose) {
        super("java", factory, verbose);
        this.pathPlacement = pathPlacement;
        this.jarsOnly = jarsOnly;
        this.group = group;
    }

    public static Java of(String... commands) {
        return of(List.of(commands));
    }

    public static Java of(PathPlacement pathPlacement, boolean jarsOnly, String... commands) {
        return of(pathPlacement, jarsOnly, List.of(commands));
    }

    public static Java of(List<String> commands) {
        return of(PathPlacement.CLASS_PATH, true, commands);
    }

    public static Java of(PathPlacement pathPlacement, boolean jarsOnly, List<String> commands) {
        return new Java(ProcessHandler.OfProcess.ofJavaHome("bin/java"), pathPlacement, jarsOnly, "main") {
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
                          PathPlacement pathPlacement,
                          boolean jarsOnly,
                          String... commands) {
        return of(factory, pathPlacement, jarsOnly, List.of(commands));
    }

    public static Java of(Function<List<String>, ProcessHandler.OfProcess> factory, List<String> commands) {
        return of(factory, PathPlacement.CLASS_PATH, true, commands);
    }

    public static Java of(Function<List<String>, ProcessHandler.OfProcess> factory,
                          PathPlacement pathPlacement,
                          boolean jarsOnly,
                          List<String> commands) {
        return new Java(factory, pathPlacement, jarsOnly, "main") {
            @Override
            protected CompletionStage<List<String>> commands(Executor executor,
                                                             BuildStepContext context,
                                                             SequencedMap<String, BuildStepArgument> arguments) {
                return CompletableFuture.completedStage(commands);
            }
        };
    }

    public Java group(String group) {
        Java self = this;
        return new Java(factory, pathPlacement, jarsOnly, group, verbose) {
            @Override
            protected CompletionStage<List<String>> commands(Executor executor,
                                                             BuildStepContext context,
                                                             SequencedMap<String, BuildStepArgument> arguments)
                    throws IOException {
                return self.commands(executor, context, arguments);
            }
        };
    }

    public Java verbose(boolean verbose) {
        Java self = this;
        return new Java(factory, pathPlacement, jarsOnly, group, verbose) {
            @Override
            protected CompletionStage<List<String>> commands(Executor executor,
                                                             BuildStepContext context,
                                                             SequencedMap<String, BuildStepArgument> arguments)
                    throws IOException {
                return self.commands(executor, context, arguments);
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
        boolean selfContainedModuleGraph = true;
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
            Path artifacts = argument.folder().resolve(ARTIFACTS);
            if (Files.isDirectory(artifacts)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(artifacts)) {
                    for (Path file : files) {
                        selfContainedModuleGraph &= !pathPlacement.place(file, modulePath, classPath);
                    }
                }
            }
            for (Path file : Dependencies.select(argument.folder(), group, "runtime")) {
                selfContainedModuleGraph &= !pathPlacement.place(file, modulePath, classPath);
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
        selfContainedModuleGraph &= classPath.isEmpty();
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
        if (!selfContainedModuleGraph) {
            prefixes.add("--add-modules");
            prefixes.add("ALL-MODULE-PATH");
        }
        return commands(executor, context, arguments).thenApplyAsync(commands -> Stream.concat(
                prefixes.stream(),
                commands.stream()).toList(), executor);
    }
}
