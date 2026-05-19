package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;
import build.jenesis.SequencedProperties;

public class Javac extends JdkProcessBuildStep {

    protected Javac(Function<List<String>, ? extends ProcessHandler> factory) {
        super("javac", factory);
    }

    public static Javac tool() {
        return new Javac(ProcessHandler.OfTool.of("javac"));
    }

    public static Javac process() {
        return new Javac(ProcessHandler.OfProcess.ofJavaHome("bin/javac"));
    }

    public static void writeRelease(Path folder, String release) throws IOException {
        if (release == null || release.isEmpty()) {
            return;
        }
        Path target = Files.createDirectories(folder.resolve(ProcessBuildStep.PROCESS));
        SequencedProperties properties = new SequencedProperties();
        properties.setProperty("--release", release);
        properties.store(target.resolve("javac.properties"));
    }

    @Override
    public CompletionStage<List<String>> process(Executor executor,
                                                 BuildStepContext context,
                                                 SequencedMap<String, BuildStepArgument> arguments,
                                                 SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        Path target = Files.createDirectory(context.next().resolve(CLASSES));
        List<String> files = new ArrayList<>(), path = new ArrayList<>(), commands = new ArrayList<>(List.of(
                "-d", target.toString()));
        for (BuildStepArgument argument : arguments.values()) {
            Path sources = argument.folder().resolve(Bind.SOURCES),
                    classes = argument.folder().resolve(CLASSES),
                    artifacts = argument.folder().resolve(ARTIFACTS);
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
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Files.createDirectories(target.resolve(sources.relativize(dir)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String name = file.toString();
                        if (name.endsWith(".java")) {
                            files.add(name);
                        } else {
                            Files.createLink(target.resolve(sources.relativize(file)), file);
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
            String escaped = String.join(File.pathSeparator, path)
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"");
            Path file = context.supplement().resolve("javac.args");
            Files.writeString(file, (module ? "--module-path" : "--class-path") + "\n\"" + escaped + "\"\n");
            commands.add("@" + file);
        }
        commands.addAll(files);
        return CompletableFuture.completedStage(commands);
    }
}
