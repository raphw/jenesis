package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.PathPlacement;
import build.jenesis.SequencedProperties;

public class NativeImage extends JdkProcessBuildStep {

    public static final String NATIVE = "native/";

    public static final String METADATA = "nativeimage/";

    private final PathPlacement pathPlacement;

    public NativeImage(PathPlacement pathPlacement) {
        this(pathPlacement, ProcessHandler.OfProcess.ofCommand("native-image"));
    }

    public NativeImage(PathPlacement pathPlacement, Function<List<String>, ? extends ProcessHandler> factory) {
        super("native-image", factory);
        this.pathPlacement = pathPlacement;
    }

    @Override
    protected CompletionStage<List<String>> process(Executor executor,
                                                    BuildStepContext context,
                                                    SequencedMap<String, BuildStepArgument> arguments,
                                                    SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        boolean modular = pathPlacement.modular();
        boolean hasAutomaticModules = false;
        String launcher = null, name = null;
        List<String> path = new ArrayList<>();
        Path config = null;
        for (BuildStepArgument argument : arguments.values()) {
            Path descriptor = argument.folder().resolve("launcher.properties");
            if (Files.isRegularFile(descriptor)) {
                SequencedProperties launcherProperties = SequencedProperties.ofFiles(descriptor);
                if (name == null) {
                    String value = launcherProperties.getProperty("name");
                    if (value != null && !value.isEmpty()) {
                        name = value;
                    }
                }
                if (launcher == null) {
                    String mainClass = launcherProperties.getProperty("mainClass");
                    String mainModule = launcherProperties.getProperty("mainModule");
                    if (mainClass != null && !mainClass.isEmpty()) {
                        launcher = modular && mainModule != null ? mainModule + "/" + mainClass : mainClass;
                    }
                }
            }
            Path artifacts = argument.folder().resolve(BuildStep.ARTIFACTS);
            if (Files.exists(artifacts)) {
                Files.walkFileTree(artifacts, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.toString().endsWith(".jar")) {
                            path.add(file.toString());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            for (Path file : Dependencies.select(argument.folder(), "runtime")) {
                path.add(file.toString());
                hasAutomaticModules |= modular && PathPlacement.automatic(file);
            }
            Path candidate = argument.folder().resolve("native-image");
            if (Files.isDirectory(candidate)) {
                config = candidate;
            }
        }
        if (launcher == null || path.isEmpty()) {
            return CompletableFuture.completedStage(null);
        }
        for (String entry : path) {
            if (entry.indexOf(File.pathSeparatorChar) != -1) {
                throw new IllegalArgumentException(
                        "Path entry contains separator '" + File.pathSeparator + "': " + entry);
            }
        }
        List<String> commands = new ArrayList<>();
        commands.add("--no-fallback");
        if (config != null) {
            commands.add("-H:ConfigurationFileDirectories=" + config);
        }
        commands.add("-o");
        commands.add(Files.createDirectories(context.next().resolve(NATIVE))
                .resolve(name == null ? "image" : name)
                .toString());
        if (modular) {
            commands.add("--module-path");
            commands.add(String.join(File.pathSeparator, path));
            if (hasAutomaticModules) {
                commands.add("--add-modules");
                commands.add("ALL-MODULE-PATH");
            }
            commands.add("--module");
            commands.add(launcher);
        } else {
            commands.add("-cp");
            commands.add(String.join(File.pathSeparator, path));
            commands.add(launcher);
        }
        return CompletableFuture.completedStage(commands);
    }
}
