package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;

public class JPackage extends JdkProcessBuildStep {

    public static final String PACKAGES = "packages/";

    private final String type;

    public JPackage(ProcessHandler.Factory factory) {
        this(factory, null);
    }

    public JPackage(ProcessHandler.Factory factory, String type) {
        super("jpackage", factory.apply("jpackage", "bin/jpackage"));
        this.type = type;
    }

    @Override
    protected CompletionStage<List<String>> process(Executor executor,
                                                    BuildStepContext context,
                                                    SequencedMap<String, BuildStepArgument> arguments,
                                                    SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        boolean modular = properties.values().stream().anyMatch(folder -> folder.containsKey("--module"));
        if (!modular && properties.values().stream().noneMatch(folder -> folder.containsKey("--main-jar"))) {
            return CompletableFuture.completedStage(null);
        }
        Path runtime = null;
        for (BuildStepArgument argument : arguments.values()) {
            Path candidate = argument.folder().resolve(JLink.RUNTIME);
            if (Files.isDirectory(candidate)) {
                runtime = candidate;
                break;
            }
        }
        if (runtime != null) {
            List<String> commands = new ArrayList<>();
            if (type != null) {
                commands.add("--type");
                commands.add(type);
            }
            commands.add("--runtime-image");
            commands.add(runtime.toString());
            commands.add("--dest");
            commands.add(Files.createDirectory(context.next().resolve(PACKAGES)).toString());
            return CompletableFuture.completedStage(commands);
        }
        Path input = Files.createDirectory(context.supplement().resolve("input"));
        SequencedMap<String, Path> staged = new LinkedHashMap<>();
        for (BuildStepArgument argument : arguments.values()) {
            for (String candidate : List.of(BuildStep.ARTIFACTS, BuildStep.DEPENDENCIES)) {
                Path folder = argument.folder().resolve(candidate);
                if (Files.exists(folder)) {
                    Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                            if (file.toString().endsWith(".jar")) {
                                String name = file.getFileName().toString();
                                Path previous = staged.putIfAbsent(name, file);
                                if (previous != null) {
                                    throw new IllegalStateException("Cannot stage two jars with the same file name '"
                                            + name + "' into a single jpackage input: " + previous + " and " + file);
                                }
                                BuildStep.linkOrCopy(input.resolve(name), file);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
        }
        if (staged.isEmpty()) {
            return CompletableFuture.completedStage(null);
        }
        List<String> commands = new ArrayList<>();
        if (type != null) {
            commands.add("--type");
            commands.add(type);
        }
        // A modular launcher (`--module`) reads its app from a `--module-path`; a classpath
        // launcher (`--main-jar`) reads it from an `--input` directory. The staged jars serve
        // as either, so only the flag differs.
        commands.add(modular ? "--module-path" : "--input");
        commands.add(input.toString());
        commands.add("--dest");
        commands.add(Files.createDirectory(context.next().resolve(PACKAGES)).toString());
        return CompletableFuture.completedStage(commands);
    }
}
