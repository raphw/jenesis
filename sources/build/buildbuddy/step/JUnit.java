package build.buildbuddy.step;

import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;

import java.io.IOException;
import java.nio.file.DirectoryStream;
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
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class JUnit extends Java {

    private final Predicate<String> isTest;

    public JUnit() {
        List<Pattern> patterns = Stream.of(".*\\.Test[a-zA-Z0-9$]*", ".*\\..*Test", ".*\\..*Tests", ".*\\..*TestCase")
                .map(Pattern::compile)
                .toList();
        this.isTest = name -> patterns.stream().anyMatch(pattern -> pattern.matcher(name).matches());
    }

    public JUnit(Predicate<String> isTest) {
        this.isTest = isTest;
    }

    public JUnit(String java, Predicate<String> isTest) {
        super(java);
        this.isTest = isTest;
    }

    @Override
    protected CompletionStage<List<String>> commands(Executor executor,
                                                     BuildStepContext context,
                                                     SequencedMap<String, BuildStepArgument> arguments)
            throws IOException {
        Target target = Target.NONE;
        for (BuildStepArgument argument : arguments.values()) {
            Path artifacts = argument.folder().resolve(ARTIFACTS);
            if (Files.exists(artifacts)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(artifacts)) {
                    for (Path file : stream) {
                        try (JarFile jarFile = new JarFile(file.toFile())) {
                            Manifest manifest = jarFile.getManifest();
                            if (manifest != null) {
                                Target candidate = switch (manifest
                                        .getMainAttributes()
                                        .getValue(Attributes.Name.IMPLEMENTATION_TITLE)) {
                                    case "JUnit" -> Target.JUNIT4;
                                    case "junit-platform-console" -> Target.JUNIT5;
                                    case null, default -> Target.NONE;
                                };
                                if (candidate.ordinal() > target.ordinal()) {
                                    target = candidate;
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        if (target == Target.NONE) {
            throw new IllegalStateException("No JUnit artifact discovered");
        }
        Target runner = target;
        List<String> commands = new ArrayList<>();
        if (modular && runner.module != null) {
            commands.add("--add-modules");
            commands.add("ALL-MODULE-PATH");
            commands.add("-m");
            commands.add(runner.module + "/" + runner.mainClass);
        } else {
            commands.add(runner.mainClass);
        }
        commands.addAll(runner.arguments);
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
                                commands.add(runner.prefix + className);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        return CompletableFuture.completedFuture(commands);
    }

    private enum Target {

        NONE(null, null, null),
        JUNIT4("junit", "org.junit.runner.JUnitCore", ""),
        JUNIT5("org.junit.platform.console",
                "org.junit.platform.console.ConsoleLauncher",
                "-select-class=",
                "execute",
                "--disable-banner",
                "--disable-ansi-colors");

        private final String module, mainClass, prefix;
        private final List<String> arguments;

        Target(String module, String mainClass, String prefix, String... arguments) {
            this.module = module;
            this.mainClass = mainClass;
            this.prefix = prefix;
            this.arguments = List.of(arguments);
        }
    }
}
