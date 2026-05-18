package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;

public class Javadoc extends JdkProcessBuildStep {

    public static final String JAVADOC = "javadoc/";

    protected Javadoc(Function<List<String>, ? extends ProcessHandler> factory) {
        super("javadoc", factory);
    }

    public static Javadoc tool() {
        return new Javadoc(ProcessHandler.OfTool.of("javadoc"));
    }

    public static Javadoc process() {
        return new Javadoc(ProcessHandler.OfProcess.ofJavaHome("bin/javadoc"));
    }

    @Override
    protected CompletionStage<List<String>> process(Executor executor,
                                                    BuildStepContext context,
                                                    SequencedMap<String, BuildStepArgument> arguments,
                                                    SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        List<String> files = new ArrayList<>(), path = new ArrayList<>(), commands = new ArrayList<>(List.of(
                "-d", Files.createDirectory(context.next().resolve(JAVADOC)).toString(),
                "-notimestamp",
                "-tag", "release:a:Release:",
                "-tag", "requires:a:Requires:",
                "-tag", "tests:X",
                "-tag", "main:X"));
        for (BuildStepArgument argument : arguments.values()) {
            Path sources = argument.folder().resolve(BuildStep.SOURCES),
                    classes = argument.folder().resolve(BuildStep.CLASSES),
                    artifacts = argument.folder().resolve(BuildStep.ARTIFACTS);
            if (Files.exists(classes)) {
                path.add(classes.toString());
            }
            if (Files.exists(artifacts)) {
                Files.walkFileTree(artifacts, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        path.add(file.toString());
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            if (Files.exists(sources)) {
                Files.walkFileTree(sources, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.toString().endsWith(".java")) {
                            files.add(file.toString());
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        boolean module = files.stream().anyMatch(file -> file.endsWith(File.separator + "module-info.java"));
        if (!path.isEmpty()) {
            for (String entry : path) {
                if (entry.indexOf(File.pathSeparatorChar) != -1) {
                    throw new IllegalArgumentException(
                            "Path entry contains separator '" + File.pathSeparator + "': " + entry);
                }
            }
            String joined = String.join(File.pathSeparator, path);
            String escaped = joined.replace("\\", "\\\\").replace("\"", "\\\"");
            Path argfile = context.supplement().resolve("javadoc.args");
            Files.writeString(argfile,
                    (module ? "--module-path" : "--class-path") + "\n\"" + escaped + "\"\n");
            commands.add("@" + argfile);
        }
        commands.addAll(files);
        return CompletableFuture.completedStage(commands);
    }
}
