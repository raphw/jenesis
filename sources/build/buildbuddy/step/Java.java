package build.buildbuddy.step;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.SequencedMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;

public abstract class Java extends ProcessBuildStep {

    protected boolean modular = true, jarsOnly = false;

    protected Java() {
        super(ProcessHandler.OfProcess.ofJavaHome("bin/java"));
    }

    protected Java(Function<List<String>, ? extends ProcessHandler> factory) {
        super(factory);
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
                                                 SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        List<String> classPath = new ArrayList<>(), modulePath = new ArrayList<>();
        for (BuildStepArgument argument : arguments.values()) {
            if (!jarsOnly) {
                for (String folder : List.of(Javac.CLASSES, Bind.RESOURCES)) {
                    Path candidate = argument.folder().resolve(folder);
                    if (Files.isDirectory(candidate)) {
                        if (modular && Files.exists(candidate.resolve("module-info.class")) ) { // TODO: multi-release?
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
        }
        List<String> prefixes = new ArrayList<>();
        prefixes.add("--enable-preview");
        if (!classPath.isEmpty()) {
            prefixes.add("-classpath");
            prefixes.add(String.join(File.pathSeparator, classPath));
        }
        if (!modulePath.isEmpty()) {
            prefixes.add("--module-path");
            prefixes.add(String.join(File.pathSeparator, modulePath));
        }
        return commands(executor, context, arguments).thenApplyAsync(commands -> Stream.concat(
                prefixes.stream(),
                commands.stream()).toList(), executor);
    }
}
