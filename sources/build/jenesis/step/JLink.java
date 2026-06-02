package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;

public class JLink extends JdkProcessBuildStep {

    public static final String RUNTIME = "runtime/";

    protected JLink(Function<List<String>, ? extends ProcessHandler> factory) {
        super("jlink", factory);
    }

    public static JLink tool() {
        return new JLink(ProcessHandler.OfTool.of("jlink"));
    }

    public static JLink process() {
        return new JLink(ProcessHandler.OfProcess.ofJavaHome("bin/jlink"));
    }

    @Override
    protected CompletionStage<List<String>> process(Executor executor,
                                                    BuildStepContext context,
                                                    SequencedMap<String, BuildStepArgument> arguments,
                                                    SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        List<String> path = new ArrayList<>();
        for (BuildStepArgument argument : arguments.values()) {
            for (String moduleFolder : List.of(JMod.JMODS, BuildStep.ARTIFACTS, BuildStep.DEPENDENCIES)) {
                Path modules = argument.folder().resolve(moduleFolder);
                if (Files.exists(modules)) {
                    Files.walkFileTree(modules, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            String name = file.toString();
                            if (name.endsWith(".jar") || name.endsWith(".jmod")) {
                                path.add(name);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
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
