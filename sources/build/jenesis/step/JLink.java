package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.PathPlacement;

public class JLink extends JdkProcessBuildStep {

    public static final String RUNTIME = "runtime/";

    private final String group;

    public JLink(ProcessHandler.Factory factory) {
        this(factory.apply("jlink", "bin/jlink"), "main", printing("jlink"));
    }

    private JLink(Function<List<String>, ? extends ProcessHandler> factory, String group, boolean verbose) {
        super("jlink", factory, verbose);
        this.group = group;
    }

    public JLink group(String group) {
        return new JLink(factory, group, verbose);
    }

    public JLink verbose(boolean verbose) {
        return new JLink(factory, group, verbose);
    }

    @Override
    protected CompletionStage<List<String>> process(Executor executor,
                                                    BuildStepContext context,
                                                    SequencedMap<String, BuildStepArgument> arguments,
                                                    SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        if (properties.values().stream().noneMatch(folder -> folder.containsKey("--add-modules"))) {
            return CompletableFuture.completedStage(null);
        }
        List<Path> jmods = new ArrayList<>(), jars = new ArrayList<>();
        for (BuildStepArgument argument : arguments.values()) {
            Path modules = argument.folder().resolve(JMod.JMODS);
            if (Files.exists(modules)) {
                Files.walkFileTree(modules, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.toString().endsWith(".jmod")) {
                            jmods.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            Path artifacts = argument.folder().resolve(BuildStep.ARTIFACTS);
            if (Files.isDirectory(artifacts)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(artifacts)) {
                    for (Path file : files) {
                        jars.add(file);
                    }
                }
            }
            for (Path file : Dependencies.select(argument.folder(), group, "runtime")) {
                jars.add(file);
            }
        }
        SequencedSet<String> modules = new LinkedHashSet<>();
        List<String> path = new ArrayList<>();
        for (Path jmod : jmods) {
            String file = jmod.getFileName().toString();
            modules.add(file.substring(0, file.length() - ".jmod".length()));
            path.add(jmod.toString());
        }
        for (Path jar : jars) {
            ModuleDescriptor descriptor = PathPlacement.moduleDescriptor(jar);
            if (descriptor == null || !modules.contains(descriptor.name())) {
                path.add(jar.toString());
            }
        }
        if (path.isEmpty()) {
            return CompletableFuture.completedStage(null);
        }
        for (String entry : path) {
            if (entry.indexOf(File.pathSeparatorChar) != -1) {
                throw new IllegalArgumentException(
                        "Path entry contains separator '" + File.pathSeparator + "': " + entry);
            }
        }
        return CompletableFuture.completedStage(new ArrayList<>(List.of(
                "--module-path", String.join(File.pathSeparator, path),
                "--output", context.next().resolve(RUNTIME).toString())));
    }
}
