package build.buildbuddy.step;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;

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
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Tests extends Java {

    private final TestEngine engine;
    private final Predicate<String> isTest;

    public Tests() {
        this(null);
    }

    public Tests(TestEngine engine) {
        this.engine = engine;
        List<Pattern> patterns = Stream.of(".*\\.Test[a-zA-Z0-9$]*", ".*\\..*Test", ".*\\..*Tests", ".*\\..*TestCase")
                .map(Pattern::compile)
                .toList();
        this.isTest = name -> patterns.stream().anyMatch(pattern -> pattern.matcher(name).matches());
    }

    public Tests(TestEngine engine, Predicate<String> isTest) {
        this.engine = engine;
        this.isTest = isTest;
    }

    public Tests(String java, TestEngine engine, Predicate<String> isTest) {
        super(java);
        this.engine = engine;
        this.isTest = isTest;
    }

    @Override
    protected CompletionStage<List<String>> commands(Executor executor,
                                                     BuildStepContext context,
                                                     SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        TestEngine engine = this.engine == null ? TestEngine
                .of(() -> arguments.values().stream().map(BuildStepArgument::folder).iterator())
                .orElseThrow(() -> new IllegalArgumentException("No test engine found")) : this.engine;
        List<String> commands = new ArrayList<>();
        if (modular && engine.module != null) {
            commands.add("--add-modules");
            commands.add("ALL-MODULE-PATH");
            commands.add("-m");
            commands.add(engine.module + "/" + engine.mainClass);
        } else {
            commands.add(engine.mainClass);
        }
        commands.addAll(engine.arguments);
        for (BuildStepArgument argument : arguments.values()) {
            Path classes = argument.folder().resolve(Javac.CLASSES);
            if (Files.exists(classes)) {
                Files.walkFileTree(classes, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.toString().endsWith(".class")) {
                            String raw = classes.relativize(file).toString();
                            String className = raw.substring(0, raw.length() - 6).replace('/', '.');
                            if (isTest.test(className)) {
                                commands.add(engine.prefix + className);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        return CompletableFuture.completedFuture(commands);
    }

}
