package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;

public abstract class Java extends JdkProcessBuildStep {

    private static final String MODULE_PATH = "--module-path", CLASS_PATH = "--class-path";

    protected final boolean modular, jarsOnly;

    protected Java() {
        this(true, false);
    }

    protected Java(boolean modular, boolean jarsOnly) {
        super("java", ProcessHandler.OfProcess.ofJavaHome("bin/java"));
        this.modular = modular;
        this.jarsOnly = jarsOnly;
    }

    protected Java(Function<List<String>, ? extends ProcessHandler> factory) {
        this(factory, true, false);
    }

    protected Java(Function<List<String>, ? extends ProcessHandler> factory, boolean modular, boolean jarsOnly) {
        super("java", factory);
        this.modular = modular;
        this.jarsOnly = jarsOnly;
    }

    public static Java of(String... commands) {
        return of(List.of(commands));
    }

    public static Java of(boolean modular, boolean jarsOnly, String... commands) {
        return of(modular, jarsOnly, List.of(commands));
    }

    public static Java of(List<String> commands) {
        return of(true, false, commands);
    }

    public static Java of(boolean modular, boolean jarsOnly, List<String> commands) {
        return new Java(modular, jarsOnly) {
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
                          boolean modular,
                          boolean jarsOnly,
                          String... commands) {
        return of(factory, modular, jarsOnly, List.of(commands));
    }

    public static Java of(Function<List<String>, ProcessHandler.OfProcess> factory, List<String> commands) {
        return of(factory, true, false, commands);
    }

    public static Java of(Function<List<String>, ProcessHandler.OfProcess> factory,
                          boolean modular,
                          boolean jarsOnly,
                          List<String> commands) {
        return new Java(factory, modular, jarsOnly) {
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
                                                              SequencedMap<String, BuildStepArgument> arguments) throws IOException;

    @Override
    public CompletionStage<List<String>> process(Executor executor,
                                                 BuildStepContext context,
                                                 SequencedMap<String, BuildStepArgument> arguments,
                                                 SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        List<String> classPath = new ArrayList<>(), modulePath = new ArrayList<>();
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            BuildStepArgument argument = entry.getValue();
            if (!jarsOnly) {
                for (String folder : List.of(Javac.CLASSES, Bind.RESOURCES)) {
                    Path candidate = argument.folder().resolve(folder);
                    if (Files.isDirectory(candidate)) {
                        if (modular && Files.exists(candidate.resolve("module-info.class"))) {
                            modulePath.add(candidate.toString());
                        } else {
                            classPath.add(candidate.toString());
                        }
                    }
                }
            }
            for (String jarFolder : List.of(ARTIFACTS, DEPENDENCIES)) {
                Path candidate = argument.folder().resolve(jarFolder);
                if (Files.exists(candidate)) {
                    Files.walkFileTree(candidate, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (modular) {
                                try (JarFile jar = new JarFile(file.toFile(),
                                        true,
                                        ZipFile.OPEN_READ,
                                        Runtime.version())) {
                                    if (jar.getEntry("module-info.class") != null
                                            || jar.getManifest() != null
                                            && jar.getManifest().getMainAttributes().getValue("Automatic-Module-Name") != null) {
                                        modulePath.add(file.toString());
                                        return FileVisitResult.CONTINUE;
                                    }
                                } catch (IllegalArgumentException _) {
                                }
                            }
                            classPath.add(file.toString());
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
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
        return commands(executor, context, arguments).thenApplyAsync(commands -> Stream.concat(
                prefixes.stream(),
                commands.stream()).toList(), executor);
    }
}
