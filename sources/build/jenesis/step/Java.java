package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;

public abstract class Java extends JdkProcessBuildStep {

    private static final String MODULE_PATH = "--module-path", CLASS_PATH = "--class-path";

    protected boolean modular = true, jarsOnly = false;

    protected Java() {
        super("java", ProcessHandler.OfProcess.ofJavaHome("bin/java"));
    }

    protected Java(Function<List<String>, ? extends ProcessHandler> factory) {
        super("java", factory);
    }

    public static Java of(String... commands) {
        return of(List.of(commands));
    }

    public static Java of(List<String> commands) {
        return new Java() {
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

    public static Java of(Function<List<String>, ProcessHandler.OfProcess> factory, List<String> commands) {
        return new Java(factory) {
            @Override
            protected CompletionStage<List<String>> commands(Executor executor,
                                                             BuildStepContext context,
                                                             SequencedMap<String, BuildStepArgument> arguments) {
                return CompletableFuture.completedStage(commands);
            }
        };
    }

    public Java modular(boolean modular) {
        this.modular = modular;
        return this;
    }

    public Java jarsOnly(boolean jarsOnly) {
        this.jarsOnly = jarsOnly;
        return this;
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
                        if (modular && Files.exists(candidate.resolve("module-info.class"))) { // TODO: multi-release?
                            modulePath.add(candidate.toString()); // TODO: does manifest apply without jar file?
                        } else {
                            classPath.add(candidate.toString());
                        }
                    }
                }
            }
            Path candidate = argument.folder().resolve(ARTIFACTS);
            if (Files.exists(candidate)) {
                Files.walkFileTree(candidate, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        if (modular) {
                            try (JarFile jar = new JarFile(file.toFile(),
                                    true,
                                    ZipFile.OPEN_READ,
                                    Runtime.version())) { // TODO: multi-release?
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
