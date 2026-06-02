package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;

public class JMod extends JdkProcessBuildStep {

    protected JMod(Function<List<String>, ? extends ProcessHandler> factory) {
        super("jmod", factory);
    }

    public static JMod tool() {
        return new JMod(ProcessHandler.OfTool.of("jmod"));
    }

    public static JMod process() {
        return new JMod(ProcessHandler.OfProcess.ofJavaHome("bin/jmod"));
    }

    @Override
    protected CompletionStage<List<String>> process(Executor executor,
                                                    BuildStepContext context,
                                                    SequencedMap<String, BuildStepArgument> arguments,
                                                    SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        List<String> path = new ArrayList<>();
        String moduleName = null;
        for (BuildStepArgument argument : arguments.values()) {
            Path classes = argument.folder().resolve(BuildStep.CLASSES);
            if (Files.exists(classes)) {
                path.add(classes.toString());
                Path moduleInfo = classes.resolve("module-info.class");
                if (moduleName == null && Files.exists(moduleInfo)) {
                    try (InputStream in = Files.newInputStream(moduleInfo)) {
                        moduleName = ModuleDescriptor.read(in).name();
                    }
                }
            }
        }
        if (moduleName == null) {
            return CompletableFuture.completedStage(null);
        }
        for (String entry : path) {
            if (entry.indexOf(File.pathSeparatorChar) != -1) {
                throw new IllegalArgumentException(
                        "Path entry contains separator '" + File.pathSeparator + "': " + entry);
            }
        }
        Path target = Files.createDirectory(context.next().resolve(BuildStep.ARTIFACTS));
        return CompletableFuture.completedStage(new ArrayList<>(List.of(
                "create",
                "--class-path", String.join(File.pathSeparator, path),
                target.resolve(moduleName + ".jmod").toString())));
    }
}
