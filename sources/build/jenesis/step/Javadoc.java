package build.jenesis.step;

import module java.base;
import build.jenesis.BuildStep;
import build.jenesis.BuildStepArgument;
import build.jenesis.BuildStepContext;

public class Javadoc extends JdkProcessBuildStep {

    public static final String JAVADOC = "javadoc/";

    private final String within;
    private final boolean classpath;

    protected Javadoc(Function<List<String>, ? extends ProcessHandler> factory) {
        this(factory, null, false);
    }

    private Javadoc(Function<List<String>, ? extends ProcessHandler> factory, String within, boolean classpath) {
        super("javadoc", factory);
        this.within = within;
        this.classpath = classpath;
    }

    public static Javadoc tool() {
        return new Javadoc(ProcessHandler.OfTool.of("javadoc"));
    }

    public static Javadoc process() {
        return new Javadoc(ProcessHandler.OfProcess.ofJavaHome("bin/javadoc"));
    }

    public Javadoc within(String within) {
        return new Javadoc(factory, within, classpath);
    }

    public Javadoc classpath() {
        return new Javadoc(factory, within, true);
    }

    @Override
    public boolean acceptableExitCode(int code,
                                      Executor executor,
                                      BuildStepContext context,
                                      SequencedMap<String, BuildStepArgument> arguments) {
        return true;
    }

    @Override
    protected CompletionStage<List<String>> process(Executor executor,
                                                    BuildStepContext context,
                                                    SequencedMap<String, BuildStepArgument> arguments,
                                                    SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        Path documentation = within == null
                ? Files.createDirectory(context.next().resolve(JAVADOC))
                : Files.createDirectories(context.next().resolve(JAVADOC).resolve(within));
        List<String> files = new ArrayList<>(), path = new ArrayList<>(), commands = new ArrayList<>(List.of(
                "-d", documentation.toString(),
                "-notimestamp",
                "-quiet",
                "-Xdoclint:none",
                "-tag", "jenesis.release:a:Release:",
                "-tag", "jenesis.main:a:Main class:",
                "-tag", "jenesis.test:a:Tests the module:",
                "-tag", "jenesis.pin:a:Pinned dependencies:"));
        for (BuildStepArgument argument : arguments.values()) {
            Path sources = argument.folder().resolve(BuildStep.SOURCES),
                    classes = argument.folder().resolve(BuildStep.CLASSES);
            if (Files.exists(classes)) {
                path.add(classes.toString());
            }
            for (String jarFolder : List.of(BuildStep.ARTIFACTS, BuildStep.DEPENDENCIES)) {
                Path jars = argument.folder().resolve(jarFolder);
                if (Files.exists(jars)) {
                    Files.walkFileTree(jars, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            path.add(file.toString());
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            }
            if (Files.exists(sources)) {
                Files.walkFileTree(sources, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.toString();
                        if (name.endsWith(".java")
                                && !(classpath && name.endsWith(File.separator + "module-info.java"))) {
                            files.add(name);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        files.sort(null);
        path.sort(null);
        boolean module = !classpath
                && files.stream().anyMatch(file -> file.endsWith(File.separator + "module-info.java"));
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
