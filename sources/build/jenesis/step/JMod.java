package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;

public class JMod extends JdkProcessBuildStep {

    public static final String JMODS = "jmods/";
    public static final String CONFIG = "jmodconfig/", LIBRARIES = "jmodlibs/", COMMANDS = "jmodcmds/";

    public JMod(ProcessHandler.Factory factory) {
        super("jmod", factory.apply("jmod", "bin/jmod"));
    }

    @Override
    protected CompletionStage<List<String>> process(Executor executor,
                                                    BuildStepContext context,
                                                    SequencedMap<String, BuildStepArgument> arguments,
                                                    SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        List<String> classPath = new ArrayList<>(), config = new ArrayList<>(), libs = new ArrayList<>(), cmds = new ArrayList<>();
        String moduleName = null;
        for (BuildStepArgument argument : arguments.values()) {
            Path classes = argument.folder().resolve(BuildStep.CLASSES);
            if (Files.isDirectory(classes)) {
                classPath.add(classes.toString());
                Path moduleInfo = classes.resolve("module-info.class");
                if (moduleName == null && Files.exists(moduleInfo)) {
                    try (InputStream in = Files.newInputStream(moduleInfo)) {
                        moduleName = ModuleDescriptor.read(in).name();
                    }
                }
            }
            collect(argument.folder().resolve(CONFIG), config);
            collect(argument.folder().resolve(LIBRARIES), libs);
            collect(argument.folder().resolve(COMMANDS), cmds);
        }
        if (moduleName == null) {
            return CompletableFuture.completedStage(null);
        }
        List<String> commands = new ArrayList<>(List.of("create"));
        option(commands, "--class-path", classPath);
        option(commands, "--config", config);
        option(commands, "--libs", libs);
        option(commands, "--cmds", cmds);
        commands.add(Files.createDirectory(context.next().resolve(JMODS)).resolve(moduleName + ".jmod").toString());
        return CompletableFuture.completedStage(commands);
    }

    private static void collect(Path folder, List<String> into) {
        if (Files.isDirectory(folder)) {
            into.add(folder.toString());
        }
    }

    private static void option(List<String> commands, String name, List<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        for (String entry : paths) {
            if (entry.indexOf(File.pathSeparatorChar) != -1) {
                throw new IllegalArgumentException(
                        "Path entry contains separator '" + File.pathSeparator + "': " + entry);
            }
        }
        commands.add(name);
        commands.add(String.join(File.pathSeparator, paths));
    }
}
