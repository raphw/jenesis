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

public class JUnit4 extends Java {

    private final Predicate<String> isTest;

    public JUnit4() {
        List<Pattern> patterns = Stream.of(".*\\.Test[a-zA-Z0-9$]*", ".*\\..*Test", ".*\\..*Tests", ".*\\..*TestCase")
                .map(Pattern::compile)
                .toList();
        this.isTest = name -> patterns.stream().anyMatch(pattern -> pattern.matcher(name).matches());
    }

    public JUnit4(String java, Predicate<String> isTest) {
        super(java);
        this.isTest = isTest;
    }

    @Override
    protected CompletionStage<List<String>> commands(Executor executor,
                                                     BuildStepContext context,
                                                     SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        List<String> commands = new ArrayList<>();
        if (modular) {
            commands.add("--add-modules");
            commands.add("ALL-MODULE-PATH");
            commands.add("-m");
            commands.add("junit/org.junit.runner.JUnitCore");
        } else {
            commands.add("org.junit.runner.JUnitCore");
        }
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
                                commands.add(className);
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
